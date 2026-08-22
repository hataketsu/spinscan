#!/usr/bin/env python3
"""Put the photographs' colour back onto a finished mesh.

The dense cloud carries a colour per point -- it came from photographs, after
all -- but every stage after fusion throws it away. Plane stripping and
component selection preserve it, and then pymeshfix and the decimator rebuild
the mesh from bare vertex and face arrays, so the colour is gone by the time
anything is exported. STL cannot carry colour at all, which is a limitation of
the format rather than of the data.

That matters more than it sounds. A grey mesh is the harshest possible way to
look at a reconstruction: every dent reads as a defect, and there is no way to
tell a real feature from a meshing artefact. The same geometry with colour on
it is legible.

Rather than trying to thread colour through the pipeline, this re-attaches it
at the end by nearest neighbour against the cloud the mesh was built from. That
works no matter what the intervening stages did, and it costs a KD-tree query
per vertex.

Usage:
  ./.venv/bin/python colorize.py projects/<name>
  ./.venv/bin/python colorize.py <mesh.stl> --cloud <fused.ply> -o <out.ply>
"""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import trimesh
from scipy.spatial import cKDTree


def load_coloured_cloud(path: Path) -> tuple[np.ndarray, np.ndarray]:
    pc = trimesh.load(path, process=False)
    pts = np.asarray(pc.vertices)
    colours = None
    visual = getattr(pc, "visual", None)
    if visual is not None and hasattr(visual, "vertex_colors"):
        c = np.asarray(visual.vertex_colors)
        if len(c) == len(pts):
            colours = c[:, :3].astype(np.uint8)
    if colours is None:
        raise SystemExit(f"ERROR: {path} không có màu cho từng điểm")
    return pts, colours


def colourise(mesh: trimesh.Trimesh, pts: np.ndarray, colours: np.ndarray,
              k: int = 5) -> trimesh.Trimesh:
    """Colour every vertex from the k nearest cloud points.

    Averaging a few neighbours rather than taking the single closest one keeps
    a stray mis-coloured point -- and the dense stage always produces some --
    from showing up as a speck on the surface.
    """
    tree = cKDTree(pts)
    k = min(k, len(pts))
    dist, idx = tree.query(mesh.vertices, k=k, workers=-1)
    if k == 1:
        rgb = colours[idx]
    else:
        # Inverse-distance weights, so a vertex sitting right on a point takes
        # that point's colour rather than a blur of its neighbourhood.
        w = 1.0 / np.maximum(dist, 1e-9)
        w /= w.sum(axis=1, keepdims=True)
        rgb = (colours[idx].astype(np.float64) * w[..., None]).sum(axis=1)
    out = mesh.copy()
    out.visual = trimesh.visual.ColorVisuals(
        mesh=out, vertex_colors=np.clip(rgb, 0, 255).astype(np.uint8))
    return out, float(np.median(dist[:, 0] if k > 1 else dist))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("target", type=Path, help="project dir hoặc file mesh")
    ap.add_argument("--cloud", type=Path, help="point cloud có màu (mặc định fused.ply)")
    ap.add_argument("-o", "--out", type=Path)
    ap.add_argument("-k", type=int, default=5, help="số điểm lân cận lấy màu")
    a = ap.parse_args()

    if a.target.is_dir():
        dense = a.target / "workspace" / "dense"
        mesh_path = dense / "auto-best.stl"
        if not mesh_path.exists():
            mesh_path = dense / "meshed-poisson.ply"
        cloud_path = a.cloud or dense / "fused.ply"
        out = a.out or dense / (mesh_path.stem + "-color.ply")
    else:
        mesh_path = a.target
        cloud_path = a.cloud or mesh_path.with_name("fused.ply")
        out = a.out or mesh_path.with_name(mesh_path.stem + "-color.ply")

    for p in (mesh_path, cloud_path):
        if not p.exists():
            raise SystemExit(f"ERROR: không thấy {p}")

    mesh = trimesh.load(mesh_path, process=False)
    mesh.metadata.clear()
    # STL stores three unshared vertices per triangle, so a 260k-face mesh
    # arrives with 780k vertices -- three colour lookups and three stored
    # colours for every corner that is really one point.
    before = len(mesh.vertices)
    mesh.merge_vertices()
    merged = before - len(mesh.vertices)
    pts, colours = load_coloured_cloud(cloud_path)

    coloured, med = colourise(mesh, pts, colours, a.k)
    coloured.export(out)

    scale = float(np.linalg.norm(pts.max(0) - pts.min(0)))
    print(f"{mesh_path.name}: {len(mesh.vertices)} đỉnh lấy màu từ "
          f"{len(pts)} điểm ({cloud_path.name})"
          + (f"  [đã gộp {merged} đỉnh trùng]" if merged else ""))
    # A vertex far from every coloured point got a colour it has no claim to,
    # which is the one way this can quietly lie.
    print(f"  khoảng cách tới điểm gần nhất, trung vị: {med / scale * 100:.3f}% cỡ vật")
    print(f"-> {out}  ({out.stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
