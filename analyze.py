#!/usr/bin/env python3
"""Judge a capture and its reconstruction, then ask an LLM what to do better.

Two halves that deliberately stay separate:

  measure()  -- deterministic numbers only (sharpness, exposure, camera ring
                coverage, SfM statistics). No model involved, so the numbers are
                reproducible and can be shown on their own.
  advise()   -- hands those numbers plus a few sample frames to the LLM and asks
                for concrete next actions in Vietnamese.

Usage:
  ./.venv/bin/python analyze.py projects/<name>            # measure + advise
  ./.venv/bin/python analyze.py projects/<name> --no-llm   # numbers only
"""
from __future__ import annotations

import argparse
import base64
import io
import json
import math
import os
import re
import subprocess
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent
IMAGE_EXT = {".jpg", ".jpeg", ".png", ".tif", ".tiff", ".webp"}

# Sharpness is the variance of a Laplacian, measured on a fixed-height grayscale
# copy. The absolute value has no meaning across cameras, but the spread within
# one capture tells you which frames drag the reconstruction down.
BLUR_HEIGHT = 512
LAPLACIAN = np.array([[0, 1, 0], [1, -4, 1], [0, 1, 0]], dtype=np.float32)


def load_env() -> dict:
    env = {}
    f = ROOT / ".env"
    if f.exists():
        for line in f.read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip()
    for k in ("OPENAI_BASE_URL", "OPENAI_API_KEY", "OPENAI_MODEL"):
        if os.environ.get(k):
            env[k] = os.environ[k]
    return env


# --------------------------------------------------------------------------- #
# image metrics
# --------------------------------------------------------------------------- #

def gray_small(path: Path, height: int = BLUR_HEIGHT) -> np.ndarray | None:
    """Grayscale array scaled to `height`, decoded as cheaply as JPEG allows."""
    try:
        im = Image.open(path)
        im.draft("L", (height, height))   # DCT-domain downscale: ~10x faster
        im = im.convert("L")
    except Exception:
        return None
    if im.height > height:
        im = im.resize((max(1, round(im.width * height / im.height)), height),
                       Image.BILINEAR)
    return np.asarray(im, dtype=np.float32)


def laplacian_var(g: np.ndarray) -> float:
    a = g[:-2, 1:-1] + g[2:, 1:-1] + g[1:-1, :-2] + g[1:-1, 2:] - 4.0 * g[1:-1, 1:-1]
    return float(a.var())


def image_metrics(image_dir: Path) -> dict:
    files = sorted(f for f in image_dir.iterdir()
                   if f.is_file() and f.suffix.lower() in IMAGE_EXT)
    rows = []
    for f in files:
        g = gray_small(f)
        if g is None:
            continue
        with Image.open(f) as im:
            w, h = im.size
        rows.append({
            "name": f.name,
            "mp": round(w * h / 1e6, 1),
            "sharp": round(laplacian_var(g), 1),
            "bright": round(float(g.mean()) / 255, 3),
            # Clipping matters more than mean exposure: blown highlights and
            # crushed shadows carry no gradient, so SIFT finds nothing there.
            "blown": round(float((g > 250).mean()), 4),
            "dark": round(float((g < 8).mean()), 4),
        })
    if not rows:
        return {"count": 0}
    sharp = np.array([r["sharp"] for r in rows])
    med = float(np.median(sharp))
    return {
        "count": len(rows),
        "megapixels": rows[0]["mp"],
        "sharpness_median": round(med, 1),
        "sharpness_p10": round(float(np.percentile(sharp, 10)), 1),
        "sharpness_p90": round(float(np.percentile(sharp, 90)), 1),
        # Half the median is roughly where a frame stops contributing features.
        "soft_frames": [r["name"] for r in rows if r["sharp"] < 0.5 * med],
        "brightness_median": round(float(np.median([r["bright"] for r in rows])), 3),
        "frames_with_blown_highlights": [r["name"] for r in rows if r["blown"] > 0.02],
        "frames_too_dark": [r["name"] for r in rows if r["dark"] > 0.20],
        "per_image": rows,
    }


# --------------------------------------------------------------------------- #
# camera ring coverage, read back out of the sparse model
# --------------------------------------------------------------------------- #

def sparse_model_dir(work: Path) -> Path | None:
    cands = [d for d in (work / "sparse").glob("*") if (d / "images.bin").exists()]
    if not cands:
        return None
    return max(cands, key=lambda d: (d / "images.bin").stat().st_size)


def camera_centers(model: Path) -> np.ndarray | None:
    """World-space camera positions, via model_converter -> images.txt."""
    out = model / "_txt"
    if not (out / "images.txt").exists():
        out.mkdir(exist_ok=True)
        rel_in = model.relative_to(ROOT)
        rel_out = out.relative_to(ROOT)
        try:
            subprocess.run(
                [str(ROOT / "colmap.sh"), "model_converter",
                 "--input_path", f"/working/{rel_in}",
                 "--output_path", f"/working/{rel_out}",
                 "--output_type", "TXT"],
                check=True, capture_output=True, timeout=300)
        except Exception:
            return None
    centers = []
    for line in (out / "images.txt").read_text().splitlines():
        if line.startswith("#") or not line.strip():
            continue
        p = line.split()
        if len(p) < 10 or not p[0].isdigit():
            continue          # every second line is the 2D point list; skip it
        qw, qx, qy, qz, tx, ty, tz = map(float, p[1:8])
        # C = -R^T t
        R = np.array([
            [1 - 2*(qy*qy + qz*qz), 2*(qx*qy - qz*qw),     2*(qx*qz + qy*qw)],
            [2*(qx*qy + qz*qw),     1 - 2*(qx*qx + qz*qz), 2*(qy*qz - qx*qw)],
            [2*(qx*qz - qy*qw),     2*(qy*qz + qx*qw),     1 - 2*(qx*qx + qy*qy)],
        ])
        centers.append(-R.T @ np.array([tx, ty, tz]))
    return np.array(centers) if centers else None


def coverage(model: Path) -> dict | None:
    """How evenly the cameras surround the object.

    Cameras are projected onto a sphere around their own centroid; the object
    sits near that centroid for a turntable-style capture. A gap in azimuth is
    the single most common reason one side of a mesh comes out flat.
    """
    C = camera_centers(model)
    if C is None or len(C) < 4:
        return None
    mid = C.mean(axis=0)
    V = C - mid
    # The capture ring's plane normal is the direction of least variance.
    _, _, Vt = np.linalg.svd(V - V.mean(axis=0), full_matrices=False)
    up = Vt[2]
    e1, e2 = Vt[0], Vt[1]
    az = np.degrees(np.arctan2(V @ e2, V @ e1)) % 360
    el = np.degrees(np.arcsin(np.clip((V @ up) / np.linalg.norm(V, axis=1), -1, 1)))
    order = np.sort(az)
    gaps = np.diff(np.concatenate([order, [order[0] + 360]]))
    r = np.linalg.norm(V, axis=1)
    return {
        "cameras": int(len(C)),
        # Azimuth is circular, so max-minus-min is meaningless: the SVD basis
        # decides where 0 deg falls, and an arc that happens to straddle it reads
        # as a full ring. The arc actually covered is everything but the widest
        # gap, which is the one quantity the wrap does not affect.
        "azimuth_span_deg": round(float(360.0 - gaps.max()), 1),
        "largest_azimuth_gap_deg": round(float(gaps.max()), 1),
        "median_azimuth_step_deg": round(float(np.median(gaps)), 1),
        "elevation_spread_deg": round(float(el.max() - el.min()), 1),
        "elevation_levels": int(round((el.max() - el.min()) / 15)) + 1,
        "distance_variation_pct": round(float(r.std() / r.mean() * 100), 1),
    }


# --------------------------------------------------------------------------- #
# SfM / dense statistics scraped from the run log
# --------------------------------------------------------------------------- #

def sfm_stats(work: Path, log: Path) -> dict:
    out: dict = {}
    if log.exists():
        # Read the tail only: patch_match floods the log with per-view blocks.
        txt = log.read_text(errors="replace")
        head = txt[:400_000]
        for key, pat in [
            ("registered_images", r"Registered images:\s*(\d+)"),
            ("points", r"Points:\s*(\d+)"),
            ("observations", r"Observations:\s*(\d+)"),
            ("mean_track_length", r"Mean track length:\s*([\d.]+)"),
            ("mean_obs_per_image", r"Mean observations per image:\s*([\d.]+)"),
            ("mean_reprojection_error_px", r"Mean reprojection error:\s*([\d.]+)"),
        ]:
            m = re.search(pat, txt)
            if m:
                out[key] = float(m.group(1)) if "." in m.group(1) else int(m.group(1))
        m = re.search(r"preset=(\w+) \| matcher=(\w+) \| max_size=(\d+)", head)
        if m:
            out["preset"], out["matcher"], out["max_size"] = \
                m.group(1), m.group(2), int(m.group(3))
        stages = re.findall(r"==> \[(\d)/6\] ([^\n]+)", txt)
        times = [float(x) for x in re.findall(r"Elapsed time: ([\d.]+) \[minutes\]", txt)]
        if times:
            out["stage_minutes"] = [round(t, 2) for t in times]
            out["total_minutes"] = round(sum(times), 1)
        out["stages_reached"] = [s[1] for s in stages]
    dense = work / "dense"
    if dense.is_dir():
        out["outputs"] = {f.name: f.stat().st_size for f in dense.iterdir()
                          if f.suffix.lower() in {".ply", ".stl"} and f.stat().st_size}
    for name in ("fused.ply", "meshed-poisson.ply"):
        f = dense / name
        if f.exists():
            head = f.read_bytes()[:600].decode("ascii", "replace")
            for el in ("vertex", "face"):
                m = re.search(rf"element {el} (\d+)", head)
                if m:
                    out.setdefault(name, {})[el] = int(m.group(1))
    return out


def measure(project: Path) -> dict:
    project = project.resolve()
    work = project / "workspace"
    data = {
        "project": project.name,
        "images": image_metrics(project / "images"),
        "sfm": sfm_stats(work, project / "run.log"),
    }
    model = sparse_model_dir(work) if work.is_dir() else None
    if model:
        data["coverage"] = coverage(model)
    imgs = data["images"]
    reg = data["sfm"].get("registered_images")
    if reg and imgs.get("count"):
        data["sfm"]["registration_rate"] = round(reg / imgs["count"], 3)
    return data


# --------------------------------------------------------------------------- #
# LLM
# --------------------------------------------------------------------------- #

SYSTEM = """Bạn là kỹ sư photogrammetry, review một lượt chụp và tái tạo bằng COLMAP.
Người dùng chụp ảnh quanh một vật thể nhỏ bằng điện thoại, chạy pipeline
feature_extractor -> matcher -> mapper -> undistort -> patch_match_stereo ->
stereo_fusion -> poisson_mesher, mục tiêu là in 3D (cần đúng khối và biến dạng,
KHÔNG cần chi tiết bề mặt) và toàn bộ pipeline phải chạy dưới 10 phút.

Trả lời bằng tiếng Việt, ngắn gọn, có số liệu dẫn chứng. Đúng 3 mục:

## Chất lượng ảnh
## Điểm yếu lớn nhất
## Việc cần làm (xếp theo mức cải thiện / công sức)

Chỉ nêu điều mà số liệu hoặc ảnh thực sự chứng minh. Nếu một chỉ số đã tốt thì
nói tốt, đừng bịa ra vấn đề. Với mỗi việc cần làm, ghi rõ làm thế nào (số ảnh,
góc chụp, hoặc tham số COLMAP cụ thể)."""


def sample_images(image_dir: Path, metrics: dict, n: int = 6) -> list[tuple[str, str]]:
    """A spread of frames through the capture, plus the softest one."""
    rows = metrics.get("per_image") or []
    if not rows:
        return []
    idx = sorted({round(i * (len(rows) - 1) / max(1, n - 2)) for i in range(n - 1)})
    worst = min(range(len(rows)), key=lambda i: rows[i]["sharp"])
    idx = sorted(set(idx) | {worst})
    out = []
    for i in idx:
        f = image_dir / rows[i]["name"]
        try:
            im = Image.open(f)
            im.draft("RGB", (768, 768))
            im = im.convert("RGB")
            im.thumbnail((768, 768), Image.LANCZOS)
            buf = io.BytesIO()
            im.save(buf, "JPEG", quality=72)
        except Exception:
            continue
        tag = rows[i]["name"] + (" (nét kém nhất)" if i == worst else "")
        out.append((tag, base64.b64encode(buf.getvalue()).decode()))
    return out


def advise(data: dict, project: Path, with_images: bool = True) -> str:
    import httpx
    env = load_env()
    if not env.get("OPENAI_API_KEY"):
        raise RuntimeError("Thiếu OPENAI_API_KEY (đặt trong .env)")

    slim = json.loads(json.dumps(data))
    slim["images"].pop("per_image", None)      # too long, and already summarised
    content: list = [{"type": "text",
                      "text": "Số liệu đo được:\n```json\n"
                              + json.dumps(slim, ensure_ascii=False, indent=1)
                              + "\n```"}]
    if with_images:
        for tag, b64 in sample_images(project / "images", data["images"]):
            content.append({"type": "text", "text": f"Ảnh mẫu: {tag}"})
            content.append({"type": "image_url",
                            "image_url": {"url": f"data:image/jpeg;base64,{b64}"}})

    r = httpx.post(
        env.get("OPENAI_BASE_URL", "https://api.openai.com/v1").rstrip("/")
        + "/chat/completions",
        headers={"Authorization": f"Bearer {env['OPENAI_API_KEY']}"},
        json={
            "model": env.get("OPENAI_MODEL", "claude-haiku-4-5"),
            "max_tokens": 1600,
            "temperature": 0.2,
            "messages": [{"role": "system", "content": SYSTEM},
                         {"role": "user", "content": content}],
        },
        timeout=180,
    )
    r.raise_for_status()
    return r.json()["choices"][0]["message"]["content"]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("project", type=Path)
    ap.add_argument("--no-llm", action="store_true")
    ap.add_argument("--no-images", action="store_true", help="gửi số liệu, không gửi ảnh")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()

    data = measure(a.project.resolve())
    if a.json:
        print(json.dumps(data, ensure_ascii=False, indent=1))
    else:
        d = json.loads(json.dumps(data))
        d["images"].pop("per_image", None)
        print(json.dumps(d, ensure_ascii=False, indent=1))
    if not a.no_llm:
        print("\n" + "=" * 60 + "\n")
        print(advise(data, a.project, with_images=not a.no_images))


if __name__ == "__main__":
    main()
