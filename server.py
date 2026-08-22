"""Local web UI for the COLMAP reconstruction rig.

Create a project, upload photos from a phone over the LAN, run the pipeline,
watch the log, download the resulting point cloud / mesh.

Run:  ./web.sh          then open http://<lan-ip>:8000 on the phone.
"""
from __future__ import annotations

import asyncio
import json
import os
import re
import shutil
import signal
import subprocess
import time
from collections import deque
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles

ROOT = Path(__file__).resolve().parent
PROJECTS = ROOT / "projects"
RECON = ROOT / "recon.sh"
IMAGE_EXT = {".jpg", ".jpeg", ".png", ".tif", ".tiff", ".heic", ".webp"}
NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

PROJECTS.mkdir(exist_ok=True)

app = FastAPI(title="COLMAP rig")

# Running pipelines, keyed by project name.
running: dict[str, subprocess.Popen] = {}


def project_dir(name: str) -> Path:
    if not NAME_RE.match(name):
        raise HTTPException(400, "Tên project không hợp lệ (chữ, số, . _ -)")
    p = PROJECTS / name
    if not p.is_dir():
        raise HTTPException(404, f"Không có project '{name}'")
    return p


def image_files(p: Path) -> list[Path]:
    d = p / "images"
    if not d.is_dir():
        return []
    return sorted(f for f in d.iterdir() if f.is_file() and f.suffix.lower() in IMAGE_EXT)


def outputs(p: Path) -> list[dict]:
    dense = p / "workspace" / "dense"
    if not dense.is_dir():
        return []
    # Skip zero-byte files: a mesher still writing its output is not a result yet.
    files = sorted(f for f in dense.iterdir()
                   if f.suffix.lower() in {".ply", ".stl"} and f.stat().st_size > 0)
    return [
        {"name": f.name, "size": f.stat().st_size,
         "kind": "stl" if f.suffix.lower() == ".stl" else
                 ("mesh" if "mesh" in f.stem else "cloud")}
        for f in files
    ]


def pipeline_alive(p: Path) -> int | None:
    """PID of this project's pipeline if it is still running, else None.

    Read from a pid file rather than the in-memory table so a server restart does
    not make a pipeline that is still churning away look finished.
    """
    f = p / ".pid"
    try:
        pid = int(f.read_text())
    except (OSError, ValueError):
        return None
    try:
        os.kill(pid, 0)
    except OSError:
        f.unlink(missing_ok=True)
        return None
    return pid


def status_of(name: str, p: Path) -> str:
    proc = running.get(name)
    if proc is not None:
        if proc.poll() is None:
            return "running"
        running.pop(name, None)
    if pipeline_alive(p):
        return "running"
    if (p / ".stopped").exists():
        return "stopped"
    if outputs(p):
        return "done"
    log = p / "run.log"
    if log.exists():
        return "failed" if "ERROR" in log.read_text(errors="replace")[-4000:] else "done"
    return "idle"


STAGE_RE = re.compile(r"^==> \[(\d)/6\] (.+)$", re.M)
VIEW_RE = re.compile(r"Processing view (\d+) */ *(\d+)")


def progress(p: Path) -> dict | None:
    """Where the pipeline is right now.

    The stage comes from a one-line file recon.sh rewrites at each step -- the
    log outgrows any tail window, so its "==> [n/6]" markers scroll out of reach.
    Within patch_match, view counts do come from the log tail.
    """
    stage_file = p / "workspace" / ".stage"
    step, label = 0, ""
    try:
        step_s, label = stage_file.read_text().strip().split("|", 1)
        if step_s == "done":
            return None
        step = int(step_s)
    except (OSError, ValueError):
        f = p / "run.log"                      # older runs predate the stage file
        if not f.exists():
            return None
        with f.open("rb") as fh:
            fh.seek(max(0, f.stat().st_size - 2_000_000))
            stages = STAGE_RE.findall(fh.read().decode(errors="replace"))
        if not stages:
            return None
        step, label = int(stages[-1][0]), stages[-1][1]

    out = {"step": step, "label": label, "done": 0, "total": 0, "pass": 0}
    if step == 5:
        f = p / "run.log"
        if not f.exists():
            return out
        with f.open("rb") as fh:
            fh.seek(max(0, f.stat().st_size - 400_000))
            tail = fh.read().decode(errors="replace")
        views = VIEW_RE.findall(tail)
        if views:
            done, total = int(views[-1][0]), int(views[-1][1])
            # patch_match walks the image list twice: photometric, then geometric.
            npass = 2 if "Writing geometric output" in tail else 1
            out |= {"done": done + (total if npass == 2 else 0),
                    "total": total * 2, "pass": npass}
    return out


def has_depth_maps(p: Path) -> int:
    d = p / "workspace" / "dense" / "stereo" / "depth_maps"
    return len(list(d.glob("*.bin"))) if d.is_dir() else 0


def describe(name: str) -> dict:
    p = PROJECTS / name
    imgs = image_files(p)
    return {
        "name": name,
        "images": len(imgs),
        "status": status_of(name, p),
        "outputs": outputs(p),
        "created": int(p.stat().st_mtime),
        "thumb": imgs[0].name if imgs else None,
        "progress": progress(p),
        "depth_maps": has_depth_maps(p),
    }


@app.get("/api/projects")
def list_projects():
    names = sorted((d.name for d in PROJECTS.iterdir() if d.is_dir()), reverse=True)
    return [describe(n) for n in names]


@app.post("/api/projects")
async def create_project(payload: dict):
    name = (payload.get("name") or "").strip()
    if not NAME_RE.match(name):
        raise HTTPException(400, "Tên project không hợp lệ (chữ, số, . _ -)")
    p = PROJECTS / name
    if p.exists():
        raise HTTPException(409, f"Project '{name}' đã tồn tại")
    (p / "images").mkdir(parents=True)
    (p / "workspace").mkdir()
    return describe(name)


@app.get("/api/projects/{name}")
def get_project(name: str):
    project_dir(name)
    return describe(name)


@app.delete("/api/projects/{name}")
def delete_project(name: str):
    p = project_dir(name)
    proc = running.get(name)
    if (proc and proc.poll() is None) or pipeline_alive(p) or tuner_alive(p):
        raise HTTPException(409, "Đang chạy, dừng trước khi xoá")
    shutil.rmtree(p)
    running.pop(name, None)
    return {"ok": True}


@app.post("/api/projects/{name}/images")
async def upload_images(name: str, files: list[UploadFile]):
    p = project_dir(name)
    dest = p / "images"
    saved = []
    for f in files:
        safe = Path(f.filename or "").name
        if not safe or Path(safe).suffix.lower() not in IMAGE_EXT:
            continue
        target = dest / safe
        # Phones reuse names like IMG_0001.jpg across batches; never overwrite.
        stem, suffix, i = target.stem, target.suffix, 1
        while target.exists():
            target = dest / f"{stem}_{i}{suffix}"
            i += 1
        with target.open("wb") as out:
            while chunk := await f.read(1 << 20):
                out.write(chunk)
        saved.append(target.name)
    return {"saved": len(saved), "total": len(image_files(p))}


@app.get("/api/projects/{name}/images")
def list_images(name: str):
    p = project_dir(name)
    return [f.name for f in image_files(p)]


@app.delete("/api/projects/{name}/images")
def clear_images(name: str):
    p = project_dir(name)
    for f in image_files(p):
        f.unlink()
    return describe(name)


MAKE_MASK = ROOT / "make_mask.py"


@app.post("/api/projects/{name}/mask")
def build_mask(name: str, payload: dict | None = None):
    """Work out which part of the frame is the turntable, and mask the rest.

    Only worth doing for a fixed-camera rig, which is exactly when it is cheap:
    the region never moves, so one mask covers every photo. Synchronous because
    it takes about fifteen seconds on a hundred frames -- long enough to notice,
    far too short to deserve a job queue.
    """
    p = project_dir(name)
    if len(image_files(p)) < 3:
        raise HTTPException(400, "Cần ít nhất 3 ảnh")
    if pipeline_alive(p):
        raise HTTPException(409, "Đang dựng hình, thử lại sau")
    opts = payload or {}
    args = [str(VENV_PY), "-u", str(MAKE_MASK), str(p)]
    if opts.get("crop", True):
        args.append("--crop")
    r = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, timeout=900)
    if r.returncode != 0:
        raise HTTPException(500, f"Tạo mask thất bại: {(r.stderr or r.stdout)[-400:]}")
    return {"log": r.stdout, "cropped": (p / "images_cropped").is_dir()}


@app.get("/api/projects/{name}/mask")
def mask_status(name: str):
    p = project_dir(name)
    preview = next((p / "masks").glob("*.png"), None) if (p / "masks").is_dir() else None
    return {
        "has_mask": preview is not None,
        "has_crop": (p / "images_cropped").is_dir(),
        "preview": preview.name if preview else None,
    }


@app.get("/api/projects/{name}/mask/preview")
def mask_preview(name: str):
    p = project_dir(name)
    f = next((p / "masks").glob("*.png"), None) if (p / "masks").is_dir() else None
    if f is None:
        raise HTTPException(404, "Chưa có mask")
    return FileResponse(f, media_type="image/png")


@app.post("/api/projects/{name}/run")
def run_pipeline(name: str, payload: dict | None = None):
    p = project_dir(name)
    if (name in running and running[name].poll() is None) or pipeline_alive(p):
        raise HTTPException(409, "Pipeline đang chạy")
    # A run wipes workspace/ wholesale, and that is exactly the tree an autotune
    # is reading from (fused.ply) and writing into (.meshcache) -- starting one
    # under the other pulls the floor out from the tuner mid-sweep.
    if tuner_alive(p):
        raise HTTPException(409, "Đang tối ưu mesh, dừng trước khi dựng lại")
    if len(image_files(p)) < 3:
        raise HTTPException(400, "Cần ít nhất 3 ảnh")

    opts = payload or {}
    env = os.environ.copy()
    env["PRESET"] = "fast" if opts.get("preset") == "fast" else "normal"
    env["MESHER"] = str(opts.get("mesher", "poisson"))
    # recon.sh falls back to the preset's own defaults for anything left empty,
    # so the fast preset stays internally consistent instead of being half
    # overridden by whatever the advanced selects happened to be showing.
    if env["PRESET"] == "normal":
        env["MATCHER"] = str(opts.get("matcher", "exhaustive"))
        env["MAX_SIZE"] = str(int(opts.get("max_size", 2000)))
    else:
        env["MATCHER"] = env["MAX_SIZE"] = ""

    # Masking and cropping are a matched pair produced by the same pass, so the
    # cropped photos are only ever fed to the mask that was cut to fit them.
    image_dir = p / "images"
    if opts.get("use_mask"):
        cropped, cropped_masks = p / "images_cropped", p / "masks_cropped"
        if cropped.is_dir() and cropped_masks.is_dir():
            image_dir, env["MASK_DIR"] = cropped, str(cropped_masks)
        elif (p / "masks").is_dir():
            env["MASK_DIR"] = str(p / "masks")
        else:
            raise HTTPException(400, "Chưa có mask — bấm Tạo mask trước")

    # Fresh workspace so a re-run never mixes with a stale sparse model.
    ws = p / "workspace"
    if ws.exists():
        shutil.rmtree(ws)
    ws.mkdir()

    (p / ".stopped").unlink(missing_ok=True)
    # A new run invalidates the cached advice -- it was written about the old one.
    (p / ".analysis.md").unlink(missing_ok=True)
    # `with`: Popen dups the descriptor into the child, so the parent's copy has
    # to be closed or every run leaks one for the life of the server process.
    with (p / "run.log").open("wb") as log:
        log.write(f"$ PRESET={env['PRESET']} MATCHER={env['MATCHER']} "
                  f"MAX_SIZE={env['MAX_SIZE']} MESHER={env['MESHER']} "
                  f"MASK_DIR={env.get('MASK_DIR', '')} "
                  f"./recon.sh {image_dir.name}\n\n".encode())
        log.flush()
        proc = subprocess.Popen(
            [str(RECON), str(image_dir), str(ws)],
            cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
            start_new_session=True,
        )
    running[name] = proc
    (p / ".pid").write_text(str(proc.pid))
    return describe(name)


# --------------------------------------------------------------------------- #
# Live view from the phone + command mailbox
# --------------------------------------------------------------------------- #
#
# The phone POSTs a preview frame every so often and long-polls for commands the
# web page drops here; the server is nothing but a one-frame buffer and a short
# queue, so the phone side stays a couple of HTTP calls.
#
# Both live in plain module-level dicts. FastAPI runs every one of these handlers
# on the same event loop, so nothing here can interleave mid-update and no lock
# is needed. It is per-process state that dies with a restart -- which is right
# for something whose whole lifetime is measured in seconds. Frames especially
# never touch disk: they are previews, not captures, and writing them a few times
# a second would churn the project directory for nothing.

LIVE_MAX = 2 << 20          # a preview frame this big is a bug, not a preview
LIVE_STALE_MS = 10_000      # older than this and we no longer call it live
CMD_MAX = 8
CMDS = {"shoot", "start", "stop", "lock", "unlock"}

live_frames: dict[str, tuple[float, bytes]] = {}
commands: dict[str, deque[str]] = {}


def live_age_ms(name: str) -> int | None:
    """Age of the buffered frame in ms, or None when there is none."""
    entry = live_frames.get(name)
    return None if entry is None else int((time.time() - entry[0]) * 1000)


@app.post("/api/projects/{name}/live")
async def put_live(name: str, request: Request):
    """Raw JPEG body, straight from the phone's preview stream."""
    project_dir(name)
    data = await request.body()
    if not data:
        raise HTTPException(400, "Khung hình rỗng")
    if len(data) > LIVE_MAX:
        raise HTTPException(413, "Khung hình quá lớn (tối đa 2 MB)")
    live_frames[name] = (time.time(), data)
    return {"ok": True, "size": len(data)}


@app.get("/api/projects/{name}/live.jpg")
def get_live(name: str):
    project_dir(name)
    age = live_age_ms(name)
    # A stale frame shown as live is worse than an honest gap: the user would
    # frame the shot against a picture of where the phone was pointing a minute ago.
    if age is None or age > LIVE_STALE_MS:
        raise HTTPException(404, "Chưa có hình trực tiếp từ điện thoại")
    return Response(live_frames[name][1], media_type="image/jpeg",
                    headers={"Cache-Control": "no-store"})


@app.get("/api/projects/{name}/live/status")
def live_status(name: str):
    """Whether the phone is streaming, without paying for a whole frame."""
    project_dir(name)
    age = live_age_ms(name)
    return {"alive": age is not None and age <= LIVE_STALE_MS,
            "age_ms": -1 if age is None else age}


@app.post("/api/projects/{name}/command")
def push_command(name: str, payload: dict | None = None):
    project_dir(name)
    cmd = str((payload or {}).get("cmd") or "")
    if cmd not in CMDS:
        raise HTTPException(400, f"Lệnh không hợp lệ: {cmd or '(trống)'}")
    # Bounded: if the phone is not collecting, a backlog of stale shutter presses
    # firing all at once the moment it reconnects is worse than dropping them.
    commands.setdefault(name, deque(maxlen=CMD_MAX)).append(cmd)
    return {"ok": True, "queued": len(commands[name])}


@app.get("/api/projects/{name}/command")
async def pop_command(name: str, wait: float = 0):
    """What the phone polls. `wait` seconds turns it into a cheap long poll."""
    project_dir(name)
    q = commands.setdefault(name, deque(maxlen=CMD_MAX))
    deadline = time.monotonic() + min(max(wait, 0), 25)
    while True:
        if q:
            return {"cmd": q.popleft()}
        if time.monotonic() >= deadline:
            return {"cmd": None}
        # Short sleeps rather than a condition variable: one queue per project and
        # a second of latency is fine, so there is nothing here worth a primitive.
        await asyncio.sleep(0.2)


# --------------------------------------------------------------------------- #
# Android app distribution (OTA)
# --------------------------------------------------------------------------- #

DIST = ROOT / "dist"


@app.get("/api/app/latest")
def app_latest():
    """Manifest the phone polls. Written by the app's :app:publishOta Gradle
    task next to the APK, so the version it advertises can never drift from the
    file it serves."""
    f = DIST / "latest.json"
    if not f.exists():
        raise HTTPException(404, "Chưa build APK nào")
    return JSONResponse(json.loads(f.read_text()))


@app.get("/api/app/download")
def app_download():
    f = DIST / "collmap.apk"
    if not f.exists():
        raise HTTPException(404, "Chưa có APK")
    return FileResponse(f, media_type="application/vnd.android.package-archive",
                        filename="collmap.apk")


# --------------------------------------------------------------------------- #
# Turntable firmware distribution (OTA)
# --------------------------------------------------------------------------- #


@app.get("/api/fw/latest")
def fw_latest():
    """Manifest the board polls. Written by the firmware Makefile next to the
    binaries, and passed through untouched -- the shape of it is the firmware's
    business, so adding a field there must not need a change here."""
    f = DIST / "turntable-fw.json"
    if not f.exists():
        raise HTTPException(404, "Chưa build firmware nào")
    return JSONResponse(json.loads(f.read_text()))


@app.get("/api/fw/download")
def fw_download():
    f = DIST / "turntable.bin"
    if not f.exists():
        raise HTTPException(404, "Chưa có firmware")
    return FileResponse(f, media_type="application/octet-stream",
                        filename="turntable.bin")


@app.get("/api/fw/download/{slot}")
def fw_download_slot(slot: str):
    """One image per A/B slot. An update is always written to the slot that is
    not currently running, and the two builds are linked at different flash
    addresses (A at 0x08002000, B at 0x08010000), so the board has to fetch the
    image built for the slot it is about to overwrite -- the other one would
    jump into nothing."""
    if slot not in ("a", "b"):
        raise HTTPException(400, "Slot không hợp lệ (chỉ a hoặc b)")
    f = DIST / f"turntable-{slot}.bin"
    if not f.exists():
        raise HTTPException(404, f"Chưa có firmware slot {slot}")
    return FileResponse(f, media_type="application/octet-stream",
                        filename=f"turntable-{slot}.bin")


# --------------------------------------------------------------------------- #
# mesh autotune
# --------------------------------------------------------------------------- #

AUTOTUNE = ROOT / "autotune.py"
# Popen handles kept so finished tuners get reaped instead of lingering as zombies.
tuners: dict[int, subprocess.Popen] = {}
VENV_PY = ROOT / ".venv" / "bin" / "python"


def tuner_alive(p: Path) -> int | None:
    """PID of a running tuner, or None.

    A finished child of this server stays in the process table as a zombie until
    someone reaps it, and os.kill(pid, 0) happily succeeds on a zombie -- which
    would leave the UI spinning forever on a job that is already done. Reap what
    we can, and read the process state for the rest.
    """
    f = p / ".tunepid"
    try:
        pid = int(f.read_text())
    except (OSError, ValueError):
        return None

    def finished() -> None:
        # Drop the stale pid file as well: it is now the only thing standing
        # between a recycled pid and this project looking permanently busy --
        # which would block run/delete, not just a second tune.
        tuners.pop(pid, None)
        f.unlink(missing_ok=True)

    proc = tuners.get(pid)
    if proc is not None and proc.poll() is not None:
        finished()
        return None
    try:
        state = (Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[0])
    except (OSError, IndexError):
        finished()
        return None
    if state == "Z":
        finished()
        return None
    return pid


@app.post("/api/projects/{name}/autotune")
def start_autotune(name: str, payload: dict | None = None):
    p = project_dir(name)
    if tuner_alive(p):
        raise HTTPException(409, "Đang tối ưu")
    if not (p / "workspace" / "dense" / "fused.ply").exists():
        raise HTTPException(400, "Chưa có fused.ply — chạy dựng hình trước")
    opts = payload or {}
    # -u: without it the child buffers its progress lines and the web log stays
    # empty until the whole four-minute sweep is over.
    args = [str(VENV_PY), "-u", str(AUTOTUNE), str(p)]
    if opts.get("scale_to"):
        args += ["--scale-to", str(float(opts["scale_to"]))]
    if opts.get("target_faces"):
        args += ["--target-faces", str(int(opts["target_faces"]))]
    if not opts.get("llm", True):
        args += ["--no-llm"]
    # The previous sweep's report is about a mesh nobody is building any more;
    # left in place the UI shows it as this run's result until the run ends.
    (p / "workspace" / "dense" / "autotune.json").unlink(missing_ok=True)
    with (p / "autotune.log").open("wb") as log:   # see run_pipeline: fd would leak
        proc = subprocess.Popen(args, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                                start_new_session=True)
    tuners[proc.pid] = proc
    (p / ".tunepid").write_text(str(proc.pid))
    return {"ok": True, "pid": proc.pid}


@app.get("/api/projects/{name}/autotune")
def autotune_status(name: str):
    p = project_dir(name)
    log = p / "autotune.log"
    report = p / "workspace" / "dense" / "autotune.json"
    # MeshFix chatters for every candidate it cannot fully close; that is
    # expected, and it would otherwise bury the progress lines.
    text = ""
    if log.exists():
        text = "\n".join(l for l in log.read_text(errors="replace").splitlines()
                          if "MeshFix" not in l)[-8000:]
    # The tuner writes autotune.json with a plain write_text, so a poll can catch
    # it half-flushed. A 500 here kills the browser's poll loop for good.
    data = None
    if report.exists():
        try:
            data = json.loads(report.read_text())
        except (OSError, ValueError):
            data = None
    return {"running": tuner_alive(p) is not None, "log": text, "report": data}


@app.post("/api/projects/{name}/analyze")
def analyze_project(name: str, payload: dict | None = None):
    """Measure the capture, then have the LLM turn the numbers into advice.

    A sync def, so FastAPI runs it in a worker thread and the log poller keeps
    ticking while the model call is out. The result is cached on disk: it costs
    an API call and the inputs only change when the photos or the run do.
    """
    p = project_dir(name)
    import analyze

    cache = p / ".analysis.md"
    if not (payload or {}).get("refresh") and cache.exists():
        return {"text": cache.read_text(), "cached": True}
    try:
        data = analyze.measure(p)
        text = analyze.advise(data, p)
    except Exception as e:                      # network, key, quota
        raise HTTPException(502, f"Phân tích thất bại: {e}")
    cache.write_text(text)
    (p / ".analysis.json").write_text(json.dumps(data, ensure_ascii=False, indent=1))
    return {"text": text, "cached": False}


@app.get("/api/projects/{name}/analysis")
def get_analysis(name: str):
    p = project_dir(name)
    f = p / ".analysis.md"
    return {"text": f.read_text() if f.exists() else "", "cached": f.exists()}


@app.post("/api/projects/{name}/stop")
def stop_pipeline(name: str):
    p = project_dir(name)
    proc = running.get(name)
    pid = proc.pid if proc and proc.poll() is None else pipeline_alive(p)
    if pid is None:
        raise HTTPException(409, "Không có pipeline đang chạy")
    os.killpg(os.getpgid(pid), signal.SIGTERM)
    (p / ".pid").unlink(missing_ok=True)
    (p / ".stopped").touch()
    return {"ok": True}


@app.get("/api/projects/{name}/log")
def get_log(name: str, offset: int = 0):
    p = project_dir(name)
    f = p / "run.log"
    if not f.exists():
        return {"text": "", "offset": 0, "status": status_of(name, p)}
    size = f.stat().st_size
    offset = min(max(offset, 0), size)
    with f.open("rb") as fh:
        fh.seek(offset)
        data = fh.read()
    return {
        "text": data.decode(errors="replace"),
        "offset": size,
        "status": status_of(name, p),
    }


@app.post("/api/projects/{name}/preview")
def make_preview(name: str, payload: dict | None = None):
    """Fuse the depth maps finished so far into a viewable cloud, mid-run."""
    p = project_dir(name)
    if not has_depth_maps(p):
        raise HTTPException(400, "Chưa có depth map nào")
    out_name = ((payload or {}).get("name") or "preview-partial.ply")
    out_name = Path(out_name).name
    if not out_name.endswith(".ply"):
        out_name += ".ply"
    r = subprocess.run([str(ROOT / "preview.sh"), name, out_name],
                       capture_output=True, text=True, cwd=ROOT, timeout=1800)
    out = p / "workspace" / "dense" / out_name
    if r.returncode != 0 or not out.exists():
        raise HTTPException(400, (r.stderr or r.stdout).strip()[-300:] or "Fuse thất bại")
    fused = [l for l in r.stdout.splitlines() if "fusing" in l or "->" in l]
    return {"name": out_name, "size": out.stat().st_size, "log": "\n".join(fused)}


@app.post("/api/projects/{name}/stl/{filename}")
def make_stl(name: str, filename: str):
    """Convert a Poisson mesh to a printable STL of just the object."""
    p = project_dir(name)
    src = p / "workspace" / "dense" / Path(filename).name
    if not src.is_file() or src.suffix.lower() != ".ply":
        raise HTTPException(404, "Không có file mesh")
    out = src.with_suffix(".stl")
    r = subprocess.run(
        [str(ROOT / ".venv" / "bin" / "python"), str(ROOT / "ply_to_stl.py"),
         str(src), "-o", str(out)],
        capture_output=True, text=True, cwd=ROOT, timeout=900,
    )
    if r.returncode != 0 or not out.exists():
        raise HTTPException(400, (r.stderr or r.stdout).strip()[-300:] or "Chuyển STL thất bại")
    return {"name": out.name, "size": out.stat().st_size, "log": r.stdout.strip()}


@app.get("/api/projects/{name}/download/{filename}")
def download(name: str, filename: str):
    p = project_dir(name)
    f = p / "workspace" / "dense" / Path(filename).name
    if not f.is_file():
        raise HTTPException(404, "Không có file")
    return FileResponse(f, filename=f"{name}-{f.name}", media_type="application/octet-stream")


@app.get("/api/projects/{name}/image/{filename}")
def image(name: str, filename: str):
    p = project_dir(name)
    f = p / "images" / Path(filename).name
    if not f.is_file():
        raise HTTPException(404, "Không có ảnh")
    return FileResponse(f)


@app.middleware("http")
async def no_cache(request, call_next):
    """Mobile browsers hold onto module scripts hard; make them revalidate."""
    resp = await call_next(request)
    if request.url.path == "/" or request.url.path.startswith("/static/"):
        resp.headers["Cache-Control"] = "no-cache, must-revalidate"
    return resp


@app.get("/", response_class=HTMLResponse)
def index():
    return (ROOT / "static" / "index.html").read_text()


app.mount("/static", StaticFiles(directory=ROOT / "static"), name="static")
