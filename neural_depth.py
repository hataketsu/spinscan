#!/usr/bin/env python3
"""Fill the holes classical MVS left, using a monocular depth prior.

`patch_match_stereo` only writes a depth where it could match a patch across
views. On smooth plastic, bare metal and glossy faces there is nothing to match,
so those pixels come back 0 and the object simply has no geometry there. A
monocular network has a learned prior and always answers -- which is exactly why
it must never be allowed to overwrite a measurement. Measured beats inferred;
inference is only for where nothing was measured.

Depth Anything V2 predicts *relative inverse* depth: no scale, no offset. The
whole job is recovering those two numbers, and the only honest source for them
is COLMAP's own depth on the pixels where COLMAP did succeed. An affine relation
holds in inverse-depth space, so the fit is done there, robustly, per image --
and an image whose fit does not convince is skipped rather than filled. A
skipped frame costs a little coverage; a wrongly scaled one poisons the fusion
for every frame that overlaps it.

    ./.venv/bin/python neural_depth.py projects/<name> [--model vits|vitb|vitl]

Output: dense/fused-neural.ply + dense/neural_depth.json in the original
project. The classical dense tree is never written to.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import time
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent
VENV_PY = ROOT / ".venv" / "bin" / "python"

MODELS = {
    "vits": "depth-anything/Depth-Anything-V2-Small-hf",
    "vitb": "depth-anything/Depth-Anything-V2-Base-hf",
    "vitl": "depth-anything/Depth-Anything-V2-Large-hf",
}


# --------------------------------------------------------------------------- #
# dependencies
# --------------------------------------------------------------------------- #

def ensure_deps() -> None:
    """Install torch/transformers into .venv only if they are actually missing.

    Torch with CUDA wheels is a multi-gigabyte download, so this checks before it
    asks. The cu124 index is pinned deliberately: the default PyPI wheel for
    Linux is CPU-only on some versions and CUDA 12.x on others, and a CPU build
    turns a 90-second job into an hour.
    """
    need_torch = need_tf = False
    try:
        import torch  # noqa: F401
        if not torch.cuda.is_available():
            print("CẢNH BÁO: torch không thấy CUDA — sẽ chạy trên CPU, rất chậm.")
    except ImportError:
        need_torch = True
    try:
        import transformers  # noqa: F401
    except ImportError:
        need_tf = True
    if need_torch:
        print("==> cài torch (CUDA 12.4)…")
        subprocess.run(["uv", "pip", "install", "--python", str(VENV_PY),
                        "torch", "torchvision",
                        "--index-url", "https://download.pytorch.org/whl/cu124"],
                       check=True)
    if need_tf:
        print("==> cài transformers…")
        subprocess.run(["uv", "pip", "install", "--python", str(VENV_PY),
                        "transformers"], check=True)
    if need_torch or need_tf:
        # The interpreter already resolved its import paths; re-exec so the new
        # packages are visible instead of failing on the import below.
        os.execv(str(VENV_PY), [str(VENV_PY), str(Path(__file__).resolve()), *sys.argv[1:]])


# --------------------------------------------------------------------------- #
# COLMAP binary formats
# --------------------------------------------------------------------------- #

def read_map(path: Path) -> np.ndarray:
    """COLMAP depth/normal map -> (h, w, c) float32.

    Header is ASCII `width&height&channels&`, then the payload. The payload is
    *slice-major* (colmap::Mat indexes `slice*w*h + row*w + col`), not
    interleaved -- reading a 3-channel normal map as interleaved gives
    plausible-looking garbage that is not even unit length.
    """
    raw = path.read_bytes()
    i, dims = 0, []
    while len(dims) < 3:
        j = raw.index(b"&", i)
        dims.append(int(raw[i:j]))
        i = j + 1
    w, h, c = dims
    a = np.frombuffer(raw, dtype=np.float32, count=w * h * c, offset=i)
    return np.transpose(a.reshape(c, h, w), (1, 2, 0))


def write_map(path: Path, arr: np.ndarray) -> None:
    h, w, c = arr.shape
    with open(path, "wb") as f:
        f.write(f"{w}&{h}&{c}&".encode())
        f.write(np.ascontiguousarray(
            np.transpose(arr, (2, 0, 1)), dtype=np.float32).tobytes())


def read_cameras(path: Path) -> dict[int, tuple[float, float, float, float]]:
    """camera_id -> (fx, fy, cx, cy). Undistorted workspaces are PINHOLE."""
    n_params = {0: 3, 1: 4, 2: 4, 3: 5}          # SIMPLE_PINHOLE, PINHOLE, SIMPLE_RADIAL, RADIAL
    d = path.read_bytes()
    o = 0
    n, = struct.unpack_from("<Q", d, o); o += 8
    out = {}
    for _ in range(n):
        cid, model = struct.unpack_from("<ii", d, o); o += 8
        o += 16                                   # width, height
        k = n_params.get(model, 4)
        p = struct.unpack_from(f"<{k}d", d, o); o += 8 * k
        if model in (0, 2, 3):
            out[cid] = (p[0], p[0], p[1], p[2])
        else:
            out[cid] = (p[0], p[1], p[2], p[3])
    return out


def read_image_cameras(path: Path) -> dict[str, int]:
    """image name -> camera_id, straight out of images.bin."""
    d = path.read_bytes()
    o = 0
    n, = struct.unpack_from("<Q", d, o); o += 8
    out = {}
    for _ in range(n):
        o += 4 + 32 + 24                          # image_id, qvec, tvec
        cid, = struct.unpack_from("<I", d, o); o += 4
        e = d.index(b"\x00", o)
        out[d[o:e].decode()] = cid
        o = e + 1
        n2, = struct.unpack_from("<Q", d, o); o += 8
        o += n2 * 24                              # skip the 2D observations
    return out


# --------------------------------------------------------------------------- #
# the fit
# --------------------------------------------------------------------------- #

def fit_affine_inverse(pred: np.ndarray, z: np.ndarray, rel_tol: float,
                       iters: int = 300, seed: int = 0) -> tuple[float, float, float, float]:
    """Fit q = s*pred + t, where q = 1/z is COLMAP's inverse depth.

    Returns (s, t, inlier_fraction, median relative error on inliers).

    Least squares on the raw pixels is not an option: a photometric depth map
    carries a long tail of gross outliers -- depths several times the scene
    extent -- and two or three of them drag the line far enough to mis-scale the
    whole frame. RANSAC on pairs finds the consensus, then one IRLS refinement
    uses every inlier instead of just the two that seeded it.

    The inlier test is *relative* (|q̂-q| < rel_tol*q). In inverse-depth space a
    relative error equals the relative error in metres, so one threshold means
    the same thing near the camera and far from it.
    """
    rng = np.random.default_rng(seed)
    q = 1.0 / z
    best = (0.0, 0.0, -1.0)
    m = len(q)
    a_idx = rng.integers(0, m, iters)
    b_idx = rng.integers(0, m, iters)
    for ia, ib in zip(a_idx, b_idx):
        dp = pred[ib] - pred[ia]
        if abs(dp) < 1e-6:
            continue
        s = (q[ib] - q[ia]) / dp
        if s <= 0:                                # nearer in the prior must mean nearer
            continue
        t = q[ia] - s * pred[ia]
        cnt = float((np.abs(s * pred + t - q) < rel_tol * q).mean())
        if cnt > best[2]:
            best = (s, t, cnt)
    s, t, frac = best
    if frac <= 0:
        return 0.0, 0.0, 0.0, 1.0

    # IRLS refinement, Tukey biweight. Two passes is enough: RANSAC already put
    # the line inside the consensus, this only recentres it on all of the data.
    for _ in range(3):
        r = (s * pred + t - q) / (rel_tol * q)
        w = np.where(np.abs(r) < 1.0, (1.0 - r ** 2) ** 2, 0.0)
        if w.sum() < 8:
            break
        sw = w.sum()
        mp, mq = (w * pred).sum() / sw, (w * q).sum() / sw
        var = (w * (pred - mp) ** 2).sum()
        if var < 1e-12:
            break
        s_new = (w * (pred - mp) * (q - mq)).sum() / var
        if s_new <= 0:
            break
        s, t = s_new, mq - s_new * mp

    resid = np.abs(s * pred + t - q) / q
    inl = resid < rel_tol
    frac = float(inl.mean())
    err = float(np.median(resid[inl])) if inl.any() else 1.0
    return float(s), float(t), frac, err


def normals_from_depth(z: np.ndarray, fx: float, fy: float,
                       cx: float, cy: float) -> tuple[np.ndarray, np.ndarray]:
    """Camera-space normals by central differences. Returns (normals, valid).

    COLMAP stores normals in the image's own camera frame, unit length, pointing
    back at the camera (dot(n, P) < 0), so that is the convention reproduced
    here. Pixels whose 4-neighbourhood is not entirely valid depth get no
    normal: a difference taken across a hole edge or a depth discontinuity
    yields a normal facing nowhere real, and fusion would happily keep the point
    anyway since it only checks that neighbours agree.
    """
    h, w = z.shape
    u, v = np.meshgrid(np.arange(w, dtype=np.float32), np.arange(h, dtype=np.float32))
    x = (u - cx) / fx * z
    y = (v - cy) / fy * z
    P = np.stack([x, y, z], axis=-1)

    ok = z > 0
    nb = np.zeros_like(ok)
    nb[1:-1, 1:-1] = (ok[1:-1, 2:] & ok[1:-1, :-2] & ok[2:, 1:-1] & ok[:-2, 1:-1]
                      & ok[1:-1, 1:-1])
    du = np.zeros_like(P); dv = np.zeros_like(P)
    du[:, 1:-1] = P[:, 2:] - P[:, :-2]
    dv[1:-1, :] = P[2:, :] - P[:-2, :]
    n = np.cross(du, dv)
    ln = np.linalg.norm(n, axis=-1, keepdims=True)
    good = nb & (ln[..., 0] > 1e-12)
    n = np.divide(n, np.where(ln > 1e-12, ln, 1.0), dtype=np.float32)
    # Flip whichever way the cross product came out; only the sign is ambiguous.
    flip = (n * P).sum(-1) > 0
    n[flip] *= -1.0
    n[~good] = 0.0
    return n.astype(np.float32), good


# --------------------------------------------------------------------------- #
# workspace
# --------------------------------------------------------------------------- #

def snapshot(dense: Path, snap: Path) -> None:
    """Parallel dense tree for the completed depth maps.

    Hardlinks, not copies, for images/ and sparse/ -- the same trick preview.sh
    uses. Those two are read-only inputs to stereo_fusion, they are ~2 GB here,
    and a link costs one inode instead of a second copy on disk. depth_maps/ and
    normal_maps/ are the one thing that must *not* be linked: writing through a
    hardlink writes into the original inode, which would silently destroy the
    classical depth maps the whole comparison rests on. Files we do not modify
    are linked; every file we modify is created fresh.
    """
    if snap.exists():
        shutil.rmtree(snap)
    (snap / "stereo").mkdir(parents=True)
    subprocess.run(["cp", "-al", str(dense / "images"), str(snap / "images")], check=True)
    subprocess.run(["cp", "-al", str(dense / "sparse"), str(snap / "sparse")], check=True)
    (snap / "stereo" / "depth_maps").mkdir()
    (snap / "stereo" / "normal_maps").mkdir()
    (snap / "stereo" / "consistency_graphs").mkdir()
    shutil.copy(dense / "stereo" / "patch-match.cfg", snap / "stereo")


def fusion_min_pixels(proj: Path, default: int = 5) -> int:
    """Recover --StereoFusion.min_num_pixels from the run that made fused.ply.

    The comparison at the end is only meaningful if both clouds come out of the
    same fusion settings; otherwise it measures the fusion flags, not the depth
    completion. COLMAP prints its options into the log, so read it back rather
    than guessing from the preset.
    """
    log = proj / "run.log"
    if not log.is_file():
        return default
    hits = re.findall(r"min_num_pixels:\s*(\d+)", log.read_text(errors="ignore"))
    return int(hits[-1]) if hits else default


# --------------------------------------------------------------------------- #

def main() -> int:
    ap = argparse.ArgumentParser(description="Neural depth completion for COLMAP holes")
    ap.add_argument("project", type=Path)
    # vits by default. vitb was measured against it and the difference did not
    # survive the noise floor of the mesh metrics; on the one metric that is
    # deterministic -- how far the filled points land from measured surface --
    # the two are within a tenth of a percent of object size. Nothing here has
    # ever shown the small model to be the limiting factor, so vitl is untried
    # on purpose: it is 1.3 GB of weights to answer a question nobody has asked.
    ap.add_argument("--model", choices=list(MODELS), default="vits")
    ap.add_argument("--max-fill", type=float, default=0.35,
                    help="bỏ khung nếu phải lấp quá tỉ lệ này của ảnh")
    ap.add_argument("--input-type", choices=["auto", "geometric", "photometric"], default="auto")
    ap.add_argument("--batch", type=int, default=8)
    ap.add_argument("--rel-tol", type=float, default=0.03,
                    help="sai số tương đối coi là khớp khi fit")
    ap.add_argument("--min-inlier", type=float, default=0.45)
    ap.add_argument("--min-valid", type=float, default=0.02,
                    help="tỉ lệ pixel COLMAP đo được tối thiểu để dám fit")
    ap.add_argument("--mask-level", type=int, default=10,
                    help="ngưỡng sáng coi là trong vật (ảnh đã bôi đen ngoài mâm)")
    ap.add_argument("--no-fusion", action="store_true")
    ap.add_argument("--no-compare", action="store_true")
    ap.add_argument("--keep-workspace", action="store_true",
                    help="giữ workspace/neural (~2 GB/project) để fuse hoặc mesh lại")
    ap.add_argument("--poisson-depth", type=int, default=9,
                    help="độ sâu Poisson dùng cho mesh so sánh (giống nhau cho cả hai)")
    a = ap.parse_args()

    ensure_deps()
    import torch
    from PIL import Image
    from scipy.ndimage import binary_erosion
    from transformers import pipeline

    proj = a.project.resolve()
    dense = proj / "workspace" / "dense"
    dm_dir = dense / "stereo" / "depth_maps"
    if not dm_dir.is_dir():
        print(f"LỖI: {proj.name} chưa có depth map dense", file=sys.stderr)
        return 1

    kind = a.input_type
    if kind == "auto":
        kind = "geometric" if any(dm_dir.glob("*.geometric.bin")) else "photometric"
    names = sorted(p.name[: -len(f".{kind}.bin")] for p in dm_dir.glob(f"*.{kind}.bin"))
    if not names:
        print(f"LỖI: không có depth map kiểu {kind}", file=sys.stderr)
        return 1

    cams = read_cameras(dense / "sparse" / "cameras.bin")
    try:
        img_cam = read_image_cameras(dense / "sparse" / "images.bin")
    except (OSError, ValueError, struct.error):
        # Only needed to tell cameras apart, and an undistorted single-camera
        # workspace has exactly one. Losing the mapping is not worth aborting on
        # if COLMAP's images.bin layout shifts again.
        img_cam = {}

    print(f"==> {proj.name}: {len(names)} ảnh, depth map {kind}, model {a.model}")
    t0 = time.time()
    cuda = torch.cuda.is_available()
    pipe = pipeline("depth-estimation", model=MODELS[a.model], device=0 if cuda else -1,
                    dtype=torch.float16 if cuda else torch.float32)
    print(f"==> nạp model: {time.time() - t0:.1f}s")

    snap = proj / "workspace" / "neural"
    snapshot(dense, snap)
    out_dm = snap / "stereo" / "depth_maps"
    out_nm = snap / "stereo" / "normal_maps"

    rows: list[dict] = []
    t0 = time.time()
    for i in range(0, len(names), a.batch):
        chunk = names[i:i + a.batch]
        imgs = [Image.open(dense / "images" / n).convert("RGB") for n in chunk]
        preds = pipe(imgs, batch_size=len(imgs))
        if isinstance(preds, dict):
            preds = [preds]
        for name, im, pr in zip(chunk, imgs, preds):
            rows.append(complete_one(
                name, im, pr, kind, dense, out_dm, out_nm,
                cams[img_cam.get(name, next(iter(cams)))], a,
                binary_erosion))
        done = min(i + a.batch, len(names))
        print(f"    {done}/{len(names)}  ({time.time() - t0:.0f}s)", end="\r", flush=True)
    print()

    filled = [r for r in rows if r["filled"]]
    skipped = [r for r in rows if not r["filled"]]
    fill_fracs = np.array([r["fill_frac"] for r in filled]) if filled else np.array([0.0])
    hole_fracs = np.array([r["hole_frac"] for r in rows])

    # Every image ends up in the snapshot: filled ones with their completed maps,
    # skipped ones with COLMAP's originals. Dropping a skipped image from fusion
    # would throw away real measurements to punish a bad fit.
    (snap / "stereo" / "fusion.cfg").write_text("\n".join(names) + "\n")

    result = {
        "project": proj.name,
        "model": MODELS[a.model],
        "input_type": kind,
        "images_total": len(names),
        "images_filled": len(filled),
        "images_skipped": len(skipped),
        "skip_reasons": {r: sum(1 for x in skipped if x["reason"] == r)
                         for r in sorted({x["reason"] for x in skipped})},
        "hole_frac_median": round(float(np.median(hole_fracs)), 5),
        "fill_frac_median": round(float(np.median(fill_fracs)), 5),
        "fill_frac_max": round(float(fill_fracs.max()), 5),
        "fit_inlier_median": round(float(np.median([r["inlier"] for r in rows])), 4),
        "fit_rel_err_median": round(float(np.median([r["fit_err"] for r in rows])), 5),
        "seconds_depth": round(time.time() - t0, 1),
    }

    if not a.no_fusion:
        min_px = fusion_min_pixels(proj)
        out_ply = dense / "fused-neural.ply"
        print(f"==> stereo_fusion (min_num_pixels={min_px}) -> {out_ply.name}")
        rel = lambda p: "/working/" + str(p.resolve().relative_to(ROOT))
        t1 = time.time()
        subprocess.run(
            [str(ROOT / "colmap.sh"), "stereo_fusion",
             "--workspace_path", rel(snap), "--workspace_format", "COLMAP",
             "--input_type", kind,
             "--StereoFusion.min_num_pixels", str(min_px),
             "--output_path", rel(out_ply)],
            check=True, stdout=subprocess.DEVNULL)
        result["fusion_min_num_pixels"] = min_px
        result["seconds_fusion"] = round(time.time() - t1, 1)
        result["points_classical"] = ply_count(dense / "fused.ply")
        result["points_neural"] = ply_count(out_ply)
        # The completed maps are ~2 GB per project and their only consumer is
        # the fusion that just ran, so they go the same way preview.sh's
        # snapshot does. --keep-workspace is for re-fusing without re-inferring.
        if not a.keep_workspace:
            shutil.rmtree(snap, ignore_errors=True)

    if not (a.no_fusion or a.no_compare):
        result["compare"] = compare(dense, a.poisson_depth)

    (dense / "neural_depth.json").write_text(json.dumps(result, indent=1, ensure_ascii=False))
    report(result)
    return 0


def compare(dense: Path, depth: int, repeats: int = 4) -> dict:
    """Same mesh recipe on both clouds, then meshkit's own metrics, side by side.

    Two things had to change from the way autotune reads these numbers, because
    the effect being measured here is roughly a percent and the raw metric is
    noisier than that:

    * One *fixed* reference cloud -- the classical one -- for both meshes.
      Scoring each mesh against its own cloud lets the inferred points vouch for
      the surface they themselves created, which is exactly the question being
      asked. It is also the larger noise source: cloud_points() subsamples and
      then RANSACs the support plane out, and two draws keep different points.
      Measured on one unchanged mesh, re-drawing the cloud swung `unsupported`
      between 0.23 and 0.36 -- ten times the difference between the two meshes.
    * Repeat the surface sampling. metrics() samples the mesh surface with an
      unseeded RNG, so a single call is a draw, not a measurement. The spread is
      reported next to the mean; a difference smaller than it means nothing.
    """
    import meshkit
    from dataclasses import replace as dc_replace

    a_ply, b_ply = dense / "fused.ply", dense / "fused-neural.ply"
    if not (a_ply.is_file() and b_ply.is_file()):
        return {}
    p = dc_replace(meshkit.Params(), poisson_depth=depth)
    # fused-neural.ply was just rewritten, and meshkit's Poisson cache is keyed
    # on depth/trim alone -- keeping it would mesh the previous run's cloud.
    shutil.rmtree(dense / f".meshcache-{b_ply.stem}", ignore_errors=True)
    meshes = {}
    for label, ply in (("classical", a_ply), ("neural", b_ply)):
        print(f"==> dựng mesh so sánh: {label}")
        # Separate cache dirs: meshkit names its Poisson cache by depth/trim
        # only, so two different clouds would otherwise collide on one file and
        # the second would silently reuse the first's mesh.
        meshes[label] = meshkit.build(ply, p, dense / f".meshcache-{ply.stem}")
    ref = meshkit.cloud_points(a_ply, seed=0)

    out = {"poisson_depth": depth, "repeats": repeats,
           "reference_points": int(len(ref)), "cloud": cloud_delta(a_ply, b_ply)}
    for label in ("classical", "neural"):
        runs = [meshkit.metrics(meshes[label], ref) for _ in range(repeats)]
        row = dict(runs[0])
        for k in ("drift_rms_pct", "coverage", "unsupported"):
            vals = [r[k] for r in runs]
            row[k] = round(float(np.mean(vals)), 5)
            row[k + "_sd"] = round(float(np.std(vals)), 5)
        row["score"] = meshkit.score(row)
        out[label] = row
    return out


# Measured, not guessed. stereo_fusion is threaded, so re-fusing an *unchanged*
# workspace still moves the cloud by ~9 points in 1.23 M -- and that alone moved
# `unsupported` by 0.039 and the meshkit score by 1.9. Across five fusions of
# effectively the same data (including one with zero filled pixels) the figure
# ranged 0.23 to 0.31. Poisson is a global solve followed by a trim, so it is
# chaotic with respect to its input point set; a mesh-level A/B below this floor
# says nothing at all, which is why cloud_delta() exists.
POISSON_NOISE = 0.08


def cloud_delta(a_ply: Path, b_ply: Path) -> dict:
    """Compare the two point clouds directly, with no meshing in between.

    This is the measurement that survives: it is deterministic, and it asks the
    only question that matters about inferred geometry -- how far from anything
    the cameras actually measured did it land. eps is meshkit's own tolerance
    (0.4% of scene extent), so "far" here means the same thing as "unsupported"
    there.
    """
    import trimesh
    from scipy.spatial import cKDTree

    pa = np.asarray(trimesh.load(a_ply, process=False).vertices)
    pb = np.asarray(trimesh.load(b_ply, process=False).vertices)
    scale = float(np.linalg.norm(pa.max(0) - pa.min(0)))
    eps = scale * 0.004
    d, _ = cKDTree(pa).query(pb, workers=-1)
    far = d > eps
    return {
        "points_classical": int(len(pa)),
        "points_neural": int(len(pb)),
        "far_points": int(far.sum()),
        "far_frac": round(float(far.mean()), 6),
        "far_dist_median_pct": round(float(np.median(d[far]) / scale * 100), 4) if far.any() else 0.0,
        "far_dist_p95_pct": round(float(np.percentile(d[far], 95) / scale * 100), 4) if far.any() else 0.0,
    }


def complete_one(name, im, pred, kind, dense, out_dm, out_nm, cam, a, binary_erosion) -> dict:
    """One image: fit, fill, write. Returns the row for the report."""
    from PIL import Image

    fx, fy, cx, cy = cam
    dm_src = dense / "stereo" / "depth_maps" / f"{name}.{kind}.bin"
    nm_src = dense / "stereo" / "normal_maps" / f"{name}.{kind}.bin"
    z = read_map(dm_src)[..., 0].copy()
    h, w = z.shape

    p = pred["predicted_depth"]
    p = p.float().cpu().numpy() if hasattr(p, "cpu") else np.asarray(p, dtype=np.float32)
    p = np.squeeze(p).astype(np.float32)
    if p.shape != (h, w):
        # The dense images and the depth maps share a size, but the model runs at
        # its own patch grid; bilinear back to the depth map's raster.
        p = np.asarray(Image.fromarray(p, mode="F").resize((w, h), Image.BILINEAR),
                       dtype=np.float32)

    # The capture app paints everything outside the turntable pure black, so the
    # subject mask is free. Erode it: JPEG ringing along that hard edge leaves a
    # halo of non-black pixels that are not surface, and filling them would hang
    # a skirt of invented geometry off the rim of the object.
    rgb = np.asarray(im, dtype=np.uint8)
    subject = rgb.max(axis=2) > a.mask_level
    subject = binary_erosion(subject, np.ones((3, 3), bool), iterations=3)

    meas = (z > 0) & subject
    holes = subject & (z <= 0)
    hole_frac = float(holes.sum()) / (h * w)
    row = {"image": name, "hole_frac": round(hole_frac, 5), "fill_frac": 0.0,
           "inlier": 0.0, "fit_err": 1.0, "filled": False, "reason": ""}

    def keep_original(reason: str) -> dict:
        row["reason"] = reason
        # Nothing to write: link COLMAP's own maps in. Safe precisely because we
        # never open these for writing.
        os.link(dm_src, out_dm / dm_src.name)
        os.link(nm_src, out_nm / nm_src.name)
        return row

    if meas.sum() < a.min_valid * h * w:
        return keep_original("ít pixel đo được")
    if not holes.any():
        return keep_original("không có lỗ")
    if hole_frac > a.max_fill:
        return keep_original("lỗ quá lớn")

    # Trim the extreme tail before fitting. RANSAC survives outliers, but a
    # photometric map's tail runs to several times the scene extent and those
    # samples are pure noise in the pair draws.
    zi = z[meas]
    pi = p[meas]
    lo, hi = np.percentile(zi, [0.5, 99.5])
    sel = (zi >= lo) & (zi <= hi)
    zi, pi = zi[sel], pi[sel]
    if len(zi) > 60000:
        idx = np.random.default_rng(0).choice(len(zi), 60000, replace=False)
        zi, pi = zi[idx], pi[idx]

    s, t, inlier, err = fit_affine_inverse(pi, zi, a.rel_tol)
    row["inlier"] = round(inlier, 4)
    row["fit_err"] = round(err, 5)
    if s <= 0 or inlier < a.min_inlier:
        return keep_original("fit kém")

    q = s * p + t
    with np.errstate(divide="ignore", invalid="ignore"):
        z_fill = np.where(q > 0, 1.0 / q, 0.0)
    # A filled depth outside the range COLMAP actually measured in this frame is
    # extrapolation, not completion.
    z_lo, z_hi = np.percentile(z[meas], [1, 99])
    ok = holes & (z_fill > 0.5 * z_lo) & (z_fill < 2.0 * z_hi)
    if not ok.any():
        return keep_original("giá trị lấp ngoài dải")

    z_new = z.copy()
    z_new[ok] = z_fill[ok].astype(np.float32)

    n_old = read_map(nm_src)
    n_new, n_ok = normals_from_depth(z_new, fx, fy, cx, cy)
    normals = n_old.copy()
    take = ok & n_ok
    normals[take] = n_new[take]
    # A filled pixel that could not get a normal has no usable geometry -- drop
    # it rather than hand fusion a depth with a zero normal.
    drop = ok & ~n_ok
    z_new[drop] = 0.0

    fill_frac = float(take.sum()) / (h * w)
    if fill_frac > a.max_fill:
        return keep_original("lỗ quá lớn")

    write_map(out_dm / dm_src.name, z_new[..., None])
    write_map(out_nm / nm_src.name, normals)
    row["fill_frac"] = round(fill_frac, 5)
    row["filled"] = True
    return row


def ply_count(path: Path) -> int:
    if not path.is_file():
        return 0
    with open(path, "rb") as f:
        for _ in range(64):
            line = f.readline()
            if line.startswith(b"element vertex"):
                return int(line.split()[2])
            if line.startswith(b"end_header"):
                break
    return 0


def report(r: dict) -> None:
    n = r["images_total"]
    print()
    print("=" * 68)
    print(f"BÙ ĐỘ SÂU BẰNG MẠNG NƠ-RON — {r['project']}")
    print("=" * 68)
    print(f"  model            : {r['model']}")
    print(f"  depth map nguồn  : {r['input_type']}")
    print(f"  ảnh đã bù        : {r['images_filled']}/{n}")
    print(f"  ảnh bị bỏ        : {r['images_skipped']}/{n}")
    for k, v in r["skip_reasons"].items():
        print(f"      - {k}: {v}")
    print(f"  lỗ trung vị      : {r['hole_frac_median'] * 100:.3f}% khung hình")
    print(f"  đã lấp trung vị  : {r['fill_frac_median'] * 100:.3f}% khung hình"
          f"  (lớn nhất {r['fill_frac_max'] * 100:.3f}%)")
    print(f"  fit: inlier trung vị {r['fit_inlier_median'] * 100:.1f}%,"
          f" sai số tương đối {r['fit_rel_err_median'] * 100:.2f}%")
    if "points_neural" in r:
        before, after = r["points_classical"], r["points_neural"]
        d = after - before
        print(f"  điểm trước       : {before:,}")
        print(f"  điểm sau         : {after:,}  ({d:+,}, "
              f"{(d / before * 100 if before else 0):+.2f}%)")
    c = r.get("compare") or {}
    if c:
        print()
        print(f"  Mesh cùng công thức (Poisson depth {c['poisson_depth']}), chấm bằng")
        print(f"  meshkit trên CÙNG đám mây cổ điển ({c['reference_points']:,} điểm),"
              f" {c['repeats']} lần lấy mẫu:")
        cols = [("classical", "cổ điển"), ("neural", "có bù")]
        print(f"    {'':<16}" + "".join(f"{lbl:>22}" for _, lbl in cols))
        for key, name, fmt, sd in (
                ("faces", "mặt", "{:,}", False),
                ("watertight", "kín", "{}", False),
                ("components", "mảnh rời", "{}", False),
                ("drift_rms_pct", "sai lệch RMS %", "{:.4f}", True),
                ("coverage", "độ phủ", "{:.4f}", True),
                ("unsupported", "không được đỡ", "{:.4f}", True),
                ("score", "điểm", "{}", False)):
            cells = []
            for ck, _ in cols:
                v = c.get(ck, {}).get(key)
                if v is None:
                    cells.append("-")
                elif sd:
                    cells.append(fmt.format(v) + " ±" + f"{c[ck][key + '_sd']:.4f}")
                else:
                    cells.append(fmt.format(v))
            print(f"    {name:<16}" + "".join(f"{x:>22}" for x in cells))
        u0, u1 = c["classical"]["unsupported"], c["neural"]["unsupported"]
        if abs(u1 - u0) < POISSON_NOISE:
            print(f"    -> chênh lệch {u1 - u0:+.4f} nằm DƯỚI sàn nhiễu của chính bước")
            print(f"       Poisson (±{POISSON_NOISE:.3f}, đo bằng cách fuse lại một workspace")
            print("       không đổi). Bảng mesh này không kết luận được gì.")
        elif u1 > u0:
            print("    -> 'không được đỡ' TĂNG vượt sàn nhiễu: mesh có thêm bề mặt mà ảnh")
            print("       chụp không chống lưng. Đúng như dự đoán khi thêm hình học suy diễn.")
        else:
            print("    -> 'không được đỡ' giảm vượt sàn nhiễu: phần bù nối lại được những lỗ")
            print("       mà Poisson vốn phải tự bắc cầu qua — vẫn là hình học suy diễn.")

        cd = c.get("cloud") or {}
        if cd:
            print()
            print("  So trực tiếp hai đám mây (không qua Poisson, nên tất định):")
            print(f"    điểm cách mọi điểm ĐO ĐƯỢC hơn 0,4 % cỡ vật:"
                  f" {cd['far_points']:,} / {cd['points_neural']:,}"
                  f" ({cd['far_frac'] * 100:.3f} %)")
            if cd["far_points"]:
                print(f"    khoảng cách đó: trung vị {cd['far_dist_median_pct']:.3f} %,"
                      f" p95 {cd['far_dist_p95_pct']:.3f} % cỡ vật")

    print()
    print("  LƯU Ý: những điểm mới là SUY DIỄN, không phải ĐO ĐẠC. Chúng do một")
    print("  mạng đơn ảnh đoán ra từ tiên nghiệm đã học, rồi được kéo về đúng tỉ lệ")
    print("  bằng chính depth map của COLMAP. Ở chỗ COLMAP đo được thì giá trị đo")
    print("  luôn được giữ nguyên. Đừng dùng phần bù này để đo kích thước.")
    print("=" * 68)


if __name__ == "__main__":
    sys.exit(main())
