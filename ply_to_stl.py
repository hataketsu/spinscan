"""Turn a COLMAP Poisson mesh into a printable STL of just the object.

The Poisson mesh covers the whole reconstructed scene: the object, the sheet of
paper it sits on, and a skirt of low-confidence surface that Poisson invents
where it had no points. STL carries geometry only, so everything that is not the
object has to go before export.

  1. fit the dominant plane (the paper) by RANSAC
  2. drop every face on or below that plane, plus the far-field skirt
  3. keep the largest remaining connected component -- the object
  4. write binary STL

Usage:
  ply_to_stl.py <mesh.ply> [-o out.stl] [--keep-plane] [--margin MM]
  ply_to_stl.py <mesh.ply> --scale-to 62      # rescale longest side to 62 mm
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import trimesh


def fit_plane(points: np.ndarray, iters: int = 400, tol_frac: float = 0.01,
              rng: np.random.Generator | None = None) -> tuple[np.ndarray, float]:
    """RANSAC the plane supported by the most points. Returns (unit normal, offset)."""
    rng = rng or np.random.default_rng(0)
    tol = tol_frac * float(np.linalg.norm(points.max(0) - points.min(0)))
    best_n, best_d, best_votes = np.array([0.0, 0.0, 1.0]), 0.0, -1
    for _ in range(iters):
        a, b, c = points[rng.choice(len(points), 3, replace=False)]
        n = np.cross(b - a, c - a)
        norm = np.linalg.norm(n)
        if norm < 1e-12:
            continue
        n = n / norm
        d = float(n @ a)
        votes = int((np.abs(points @ n - d) < tol).sum())
        if votes > best_votes:
            best_n, best_d, best_votes = n, d, votes
    return best_n, best_d


def strip_plane(mesh: trimesh.Trimesh, margin_frac: float = 0.015) -> trimesh.Trimesh:
    """Remove the support plane and everything beneath it."""
    v = mesh.vertices
    n, d = fit_plane(v)
    height = v @ n - d
    # Orient so the object sits on the positive side (more spread above the plane).
    if np.percentile(height, 99) < -np.percentile(height, 1):
        n, d, height = -n, -d, -height
    margin = margin_frac * float(np.linalg.norm(v.max(0) - v.min(0)))
    keep_v = height > margin
    keep_f = keep_v[mesh.faces].all(axis=1)
    if not keep_f.any():
        print("  plane fit kept nothing; leaving mesh intact", file=sys.stderr)
        return mesh
    out = mesh.copy()
    out.update_faces(keep_f)
    out.remove_unreferenced_vertices()
    print(f"  plane removed: {len(mesh.faces)} -> {len(out.faces)} faces")
    return out


def largest_component(mesh: trimesh.Trimesh) -> trimesh.Trimesh:
    """Keep only the biggest connected piece.

    Deliberately not mesh.split(): that calls submesh() per component, and
    submesh deep-copies mesh.metadata every time. For a PLY, metadata still
    holds the raw vertex array, so a Poisson mesh with thousands of stray
    fragments deep-copies tens of gigabytes and the process gets OOM-killed.
    Working on the face-adjacency graph touches no vertex data at all.
    """
    from trimesh.graph import connected_components

    comps = connected_components(mesh.face_adjacency,
                                 nodes=np.arange(len(mesh.faces)))
    if len(comps) <= 1:
        return mesh
    biggest = max(comps, key=len)
    mask = np.zeros(len(mesh.faces), dtype=bool)
    mask[biggest] = True
    out = mesh.copy()
    out.update_faces(mask)
    out.remove_unreferenced_vertices()
    print(f"  {len(comps)} components, kept largest "
          f"({len(out.faces)} of {len(mesh.faces)} faces)")
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("mesh", type=Path)
    ap.add_argument("-o", "--out", type=Path)
    ap.add_argument("--keep-plane", action="store_true",
                    help="export the whole scene, do not isolate the object")
    ap.add_argument("--margin", type=float, default=1.5,
                    help="cut this %% of the scene size above the plane (default 1.5)")
    ap.add_argument("--scale-to", type=float,
                    help="rescale so the longest side equals this many mm")
    args = ap.parse_args()

    loaded = trimesh.load(args.mesh, process=False)
    if isinstance(loaded, trimesh.PointCloud) or not getattr(loaded, "faces", None) is not None:
        print(f"{args.mesh.name} is a point cloud, not a mesh. Mesh it first:\n"
              f"  ./colmap.sh poisson_mesher --input_path <ply> --output_path <mesh.ply>",
              file=sys.stderr)
        return 2
    mesh: trimesh.Trimesh = loaded
    mesh.metadata.clear()          # drops the raw PLY array trimesh keeps around
    print(f"{args.mesh.name}: {len(mesh.vertices)} verts, {len(mesh.faces)} faces")

    if not args.keep_plane:
        mesh = strip_plane(mesh, args.margin / 100.0)
        mesh = largest_component(mesh)

    mesh.update_faces(mesh.nondegenerate_faces())   # trimesh 5 dropped the
    mesh.update_faces(mesh.unique_faces())           # remove_* helpers
    mesh.fix_normals()

    if args.scale_to:
        extent = float(mesh.extents.max())
        if extent > 0:
            mesh.apply_scale(args.scale_to / extent)
            print(f"  scaled longest side to {args.scale_to} mm")

    out = args.out or args.mesh.with_suffix(".stl")
    mesh.export(out)
    print(f"-> {out}  ({out.stat().st_size/1e6:.1f} MB, {len(mesh.faces)} faces)")
    print(f"   size: {np.round(mesh.extents, 2)}  watertight: {mesh.is_watertight}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
