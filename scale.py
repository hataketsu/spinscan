#!/usr/bin/env python3
"""Recover real-world scale from the turntable itself.

Structure from motion has no idea how big anything is. It solves shape and
camera geometry up to one unknown factor, so every mesh this rig produces comes
out in arbitrary units -- fine to look at, useless to print, because a slicer
needs millimetres.

The usual fixes are to put a ruler in the shot or to measure the object and pass
the number in. Neither is needed here: the object is already sitting on a disc
of known diameter in every single frame, and that disc is the most densely
reconstructed thing in the scene. Measure it in model units, divide, done.

Robustness comes from where the points are rather than from clever fitting. The
mat is the dominant plane by a wide margin, so RANSAC finds it immediately; the
disc is a circle, so radius from the centroid is a one-dimensional quantity with
a hard edge; and the 99th percentile of that radius ignores the handful of
stray points the dense stage always scatters past the rim.

Usage:
  ./.venv/bin/python scale.py projects/<name> --disc-mm 200
  ./.venv/bin/python scale.py projects/<name>/workspace/dense/fused.ply --disc-mm 200
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import trimesh

import meshkit


def disc_diameter(cloud: np.ndarray) -> tuple[float, dict]:
    """Diameter of the support disc, in model units."""
    n, d, tol = meshkit.fit_plane(cloud)
    signed = cloud @ n - d
    on_plane = cloud[np.abs(signed) < tol]
    if len(on_plane) < 500:
        raise RuntimeError("không tìm thấy mặt phẳng nền đủ dày để đo")

    # Two axes spanning the plane, so radius is measured in the plane rather
    # than through the object standing on it.
    centred = on_plane - on_plane.mean(axis=0)
    _, _, vt = np.linalg.svd(centred, full_matrices=False)
    e1, e2 = vt[0], vt[1]
    xy = np.stack([centred @ e1, centred @ e2], axis=1)

    # Re-centre on the circle rather than on the point cloud: the mat is not
    # sampled evenly, and a centroid pulled towards the denser side would
    # shorten every radius measured from it.
    centre = xy.mean(axis=0)
    for _ in range(6):
        r = np.linalg.norm(xy - centre, axis=1)
        keep = r > np.percentile(r, 60)          # weight the rim, not the middle
        centre = xy[keep].mean(axis=0)

    r = np.linalg.norm(xy - centre, axis=1)
    radius = float(np.percentile(r, 99))
    stats = {
        "plane_points": int(len(on_plane)),
        "radius_p99": round(radius, 6),
        "radius_p50": round(float(np.percentile(r, 50)), 6),
        # A disc sampled to its rim has p50/p99 near 1/sqrt(2) = 0.707, because
        # half the area of a circle lies inside that fraction of its radius. Far
        # from it means the plane found is not a disc, and the number below is
        # not a diameter.
        "fill_ratio": round(float(np.percentile(r, 50) / radius), 3),
    }
    return radius * 2.0, stats


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("target", type=Path, help="project dir hoặc file .ply")
    ap.add_argument("--disc-mm", type=float, required=True,
                    help="đường kính thật của đĩa xoay, tính bằng mm")
    ap.add_argument("--apply", type=Path,
                    help="ghi một bản .stl đã scale sang mm từ mesh này")
    a = ap.parse_args()

    fused = a.target
    if fused.is_dir():
        fused = fused / "workspace" / "dense" / "fused.ply"
    if not fused.exists():
        raise SystemExit(f"ERROR: không thấy {fused}")

    # The plane is what is being measured here, so keep it.
    cloud = meshkit.cloud_points(fused, drop_plane=False)
    diameter, stats = disc_diameter(cloud)
    mm_per_unit = a.disc_mm / diameter

    # A disc sampled to its rim sits at 0.707. Outside a tight band around that
    # the plane is not a clean circle -- usually because the room is still in
    # the cloud, in which case every number below is measuring the room.
    if not 0.62 < stats["fill_ratio"] < 0.80:
        print(f"  CẢNH BÁO: fill_ratio={stats['fill_ratio']} (đĩa thật ~0.707) — "
              f"mặt phẳng tìm được không phải hình tròn sạch.\n"
              f"  Gần như chắc chắn nền vẫn còn trong đám mây điểm; chạy "
              f"make_mask.py --crop rồi dựng lại thì số dưới đây mới tin được.")

    obj = meshkit.cloud_points(fused, drop_plane=True)
    extents = (obj.max(0) - obj.min(0)) * mm_per_unit

    out = {
        "disc_mm": a.disc_mm,
        "disc_units": round(diameter, 6),
        "mm_per_unit": round(mm_per_unit, 6),
        "object_mm": [round(float(x), 1) for x in sorted(extents, reverse=True)],
        **stats,
    }
    print(json.dumps(out, ensure_ascii=False, indent=1))
    print(f"\nĐĩa {a.disc_mm:.0f} mm = {diameter:.4f} đơn vị model "
          f"-> 1 đơn vị = {mm_per_unit:.3f} mm")
    print(f"Vật thể: {extents.max():.1f} x {sorted(extents)[1]:.1f} x "
          f"{extents.min():.1f} mm")

    if a.apply:
        mesh = trimesh.load(a.apply if a.apply.exists() else fused, process=False)
        mesh.metadata.clear()
        mesh.apply_scale(mm_per_unit)
        target = a.apply.with_name(a.apply.stem + "-mm.stl")
        mesh.export(target)
        print(f"-> {target}  ({', '.join(f'{v:.1f}' for v in mesh.extents)} mm)")


if __name__ == "__main__":
    main()
