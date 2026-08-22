#!/usr/bin/env python3
"""Parametric point-cloud -> printable-STL stage, plus objective quality metrics.

Everything the autotuner is allowed to turn lives in Params. build() is pure in
the sense that matters: same fused.ply + same Params -> same mesh, so a search
over Params is reproducible and results can be cached by their parameter hash.

Order matters and is fixed:
  poisson -> strip support plane -> keep components -> repair -> smooth
  -> decimate -> scale
Repair before smoothing, because a hole edge pulls a smoothing filter inwards
and leaves a lip around it; decimate after smoothing, because smoothing a
decimated mesh just rounds off the corners the decimation kept.
"""
from __future__ import annotations

import hashlib
import json
import subprocess
import time
from dataclasses import dataclass, asdict, replace
from pathlib import Path

import numpy as np
import trimesh
from scipy.spatial import cKDTree

ROOT = Path(__file__).resolve().parent


# --------------------------------------------------------------------------- #

@dataclass(frozen=True)
class Params:
    # Poisson surface reconstruction (re-run from the dense cloud)
    poisson_depth: int = 9
    poisson_trim: float = 10.0
    # Support-plane removal. margin is in units of the RANSAC inlier band, so it
    # scales with the cloud's own noise instead of with the (unknown) object size.
    strip_plane: bool = True
    plane_margin: float = 1.0
    # Components to keep: "largest", or a fraction of the largest to survive.
    min_component_frac: float = 0.0      # 0 => keep only the largest
    # none | cap | meshfix
    repair: str = "cap"
    smooth_iters: int = 0                # Taubin, shape-preserving
    target_faces: int = 0                # 0 => no decimation
    scale_to_mm: float = 0.0             # 0 => leave in COLMAP units

    def key(self) -> str:
        return hashlib.sha1(json.dumps(asdict(self), sort_keys=True).encode()).hexdigest()[:10]


# --------------------------------------------------------------------------- #
# stages
# --------------------------------------------------------------------------- #

def poisson(fused: Path, out: Path, depth: int, trim: float) -> Path:
    """Re-mesh the dense cloud. Cheap enough (~15 s at depth 9) to sit inside a
    search loop, which is the whole reason depth/trim are tunable at all."""
    if out.exists() and out.stat().st_size:
        return out
    rel = lambda p: "/working/" + str(p.resolve().relative_to(ROOT))
    subprocess.run(
        [str(ROOT / "colmap.sh"), "poisson_mesher",
         "--input_path", rel(fused), "--output_path", rel(out),
         "--PoissonMeshing.depth", str(depth),
         "--PoissonMeshing.trim", str(trim)],
        check=True, capture_output=True, timeout=1800)
    return out


def fit_plane(pts: np.ndarray, iters: int = 400, seed: int = 0) -> tuple[np.ndarray, float, float]:
    """RANSAC dominant plane. Returns (normal, offset, inlier band width)."""
    rng = np.random.default_rng(seed)
    scale = np.linalg.norm(pts.max(0) - pts.min(0))
    tol = scale * 0.004
    best_n, best_d, best_cnt = np.array([0.0, 0.0, 1.0]), 0.0, -1
    for _ in range(iters):
        a, b, c = pts[rng.choice(len(pts), 3, replace=False)]
        n = np.cross(b - a, c - a)
        ln = np.linalg.norm(n)
        if ln < 1e-12:
            continue
        n = n / ln
        d = float(n @ a)
        cnt = int((np.abs(pts @ n - d) < tol).sum())
        if cnt > best_cnt:
            best_n, best_d, best_cnt = n, d, cnt
    return best_n, best_d, tol


def strip_support_plane(m: trimesh.Trimesh, margin: float) -> trimesh.Trimesh:
    """Drop the sheet the object was standing on.

    The plane is fitted to the mesh's own vertices; the object then has to be on
    the side of it holding fewer points, which is what distinguishes a table top
    from a flat face of the object itself.
    """
    v = m.vertices
    n, d, tol = fit_plane(v)
    signed = v @ n - d
    band = tol * max(margin, 0.1)
    above, below = (signed > band).sum(), (signed < -band).sum()
    if max(above, below) < 0.05 * len(v):
        return m                                  # no plane worth removing
    # The band itself is the sheet; the object is whichever side of it still
    # holds bulk geometry. The other side is stray Poisson skin under the table.
    keep_side = signed > band if above > below else signed < -band
    if keep_side.sum() < 0.02 * len(v):
        return m
    face_keep = keep_side[m.faces].all(axis=1)
    if not face_keep.any():
        return m
    out = m.copy()
    out.update_faces(face_keep)
    out.remove_unreferenced_vertices()
    return out


def keep_components(m: trimesh.Trimesh, min_frac: float) -> trimesh.Trimesh:
    """Largest connected piece, or every piece at least min_frac of it.

    Works on the face-adjacency graph rather than mesh.split(): split() calls
    submesh() per component and submesh deep-copies mesh.metadata each time,
    which for a PLY still holds the raw vertex array. Thousands of Poisson
    crumbs then copy tens of gigabytes and the process is OOM-killed.
    """
    from trimesh.graph import connected_components
    comps = connected_components(m.face_adjacency, nodes=np.arange(len(m.faces)))
    if len(comps) <= 1:
        return m
    biggest = max(len(c) for c in comps)
    thr = biggest if min_frac <= 0 else max(1, int(min_frac * biggest))
    mask = np.zeros(len(m.faces), dtype=bool)
    for c in comps:
        if len(c) >= thr:
            mask[c] = True
    out = m.copy()
    out.update_faces(mask)
    out.remove_unreferenced_vertices()
    return out


def cap_boundaries(m: trimesh.Trimesh) -> trimesh.Trimesh:
    """Close every open boundary loop with a fan from its own centroid.

    The big hole is always the footprint where the support plane was cut away,
    and a fan across it is exactly the flat base a printer wants. trimesh's
    fill_holes only handles triangle- and quad-sized gaps, so it cannot do this.
    """
    m = m.copy()
    trimesh.repair.fill_holes(m)              # cheap cases first
    for _ in range(6):
        loops = boundary_loops(m)
        if not loops:
            break
        # Only the fans are built as Python lists. list(m.vertices) turns every
        # row into its own numpy object -- around fifteen times the array's own
        # footprint, which on a multi-million-face candidate is hundreds of MB
        # per pass for data that is copied back out unchanged.
        base = len(m.vertices)
        new_verts, new_faces = [], []
        for loop in loops:
            if len(loop) < 3:
                continue
            ci = base + len(new_verts)
            new_verts.append(m.vertices[loop].mean(axis=0))
            for a, b in zip(loop, loop[1:] + loop[:1]):
                new_faces.append([a, b, ci])
        if not new_faces:
            break
        m = trimesh.Trimesh(
            vertices=np.vstack([m.vertices, np.array(new_verts)]),
            faces=np.vstack([m.faces, np.array(new_faces, dtype=m.faces.dtype)]),
            process=False)
    trimesh.repair.fix_normals(m)
    return m


def boundary_loops(m: trimesh.Trimesh) -> list[list[int]]:
    """Ordered vertex loops along edges used by exactly one face."""
    edges = m.edges_sorted
    _, idx, cnt = np.unique(edges, axis=0, return_index=True, return_counts=True)
    open_edges = edges[idx[cnt == 1]]
    if not len(open_edges):
        return []
    nbr: dict[int, list[int]] = {}
    for a, b in open_edges:
        nbr.setdefault(int(a), []).append(int(b))
        nbr.setdefault(int(b), []).append(int(a))
    seen, loops = set(), []
    for start in nbr:
        if start in seen:
            continue
        loop, cur, prev = [start], start, None
        seen.add(start)
        while True:
            nxt = next((x for x in nbr[cur] if x != prev and x not in seen), None)
            if nxt is None:
                break
            loop.append(nxt)
            seen.add(nxt)
            prev, cur = cur, nxt
        if len(loop) >= 3:
            loops.append(loop)
    return loops


def meshfix(m: trimesh.Trimesh) -> trimesh.Trimesh:
    """Force watertight-and-manifold. Stronger than capping, and blunter: it will
    also swallow thin features it decides are non-manifold spikes."""
    import pymeshfix
    v, f = pymeshfix.clean_from_arrays(np.asarray(m.vertices), np.asarray(m.faces))
    out = trimesh.Trimesh(vertices=v, faces=f, process=False)
    trimesh.repair.fix_normals(out)
    return out


def decimate(m: trimesh.Trimesh, target: int) -> trimesh.Trimesh:
    if target <= 0 or len(m.faces) <= target:
        return m
    import fast_simplification
    v, f = fast_simplification.simplify(
        np.asarray(m.vertices, dtype=np.float32), np.asarray(m.faces, dtype=np.int32),
        target_reduction=1.0 - target / len(m.faces))
    return trimesh.Trimesh(vertices=v, faces=f, process=False)


def build(fused: Path, p: Params, cache: Path) -> trimesh.Trimesh:
    cache.mkdir(parents=True, exist_ok=True)
    raw = poisson(fused, cache / f"poisson-d{p.poisson_depth}-t{p.poisson_trim:g}.ply",
                  p.poisson_depth, p.poisson_trim)
    m = trimesh.load(raw, process=False)
    # A PLY keeps its raw vertex buffer in metadata; anything that copies the
    # mesh downstream would drag that along.
    m.metadata.clear()
    m.update_faces(m.nondegenerate_faces())
    m.update_faces(m.unique_faces())

    if p.strip_plane:
        m = strip_support_plane(m, p.plane_margin)
    m = keep_components(m, p.min_component_frac)

    # Repair is the one stage that can destroy real geometry, so measure what it
    # did. Closing one open boundary raises the Euler characteristic by 1;
    # plugging a tunnel raises it by 2. Anything past one-per-boundary is a
    # through-hole that got filled in -- on a bracket, that is a mounting hole.
    tunnels_lost = 0
    euler_before, loops_before = int(m.euler_number), len(boundary_loops(m))
    if p.repair == "cap":
        m = cap_boundaries(m)
    elif p.repair == "meshfix":
        m = meshfix(m)
    if p.repair != "none":
        tunnels_lost += max(0, (int(m.euler_number) - euler_before - loops_before) // 2)
    if p.smooth_iters:
        # Taubin alternates a shrinking and an expanding pass, so the object does
        # not deflate the way plain Laplacian smoothing makes it.
        trimesh.smoothing.filter_taubin(m, iterations=int(p.smooth_iters))
    if p.target_faces:
        euler_pre = int(m.euler_number)
        m = decimate(m, p.target_faces)
        # Quadric decimation collapses edges without regard for manifoldness, so
        # a watertight mesh can come out of it with seams. Re-close afterwards,
        # otherwise asking for fewer faces silently costs printability.
        if p.repair == "cap":
            m = cap_boundaries(m)
        elif p.repair == "meshfix":
            m = meshfix(m)
        # Collapsing edges down to a face budget can swallow a small hole whole,
        # so the same accounting applies to the decimate + re-close pair.
        tunnels_lost += max(0, (int(m.euler_number) - euler_pre) // 2)
    if p.scale_to_mm:
        m.apply_scale(p.scale_to_mm / max(m.extents))
    # Stashed here because every stage above may rebuild the Trimesh, and the
    # count has to survive to metrics().
    m.metadata["tunnels_lost"] = tunnels_lost
    return m


# --------------------------------------------------------------------------- #
# objective quality
# --------------------------------------------------------------------------- #

def cloud_points(fused: Path, limit: int = 300_000, seed: int = 0,
                 drop_plane: bool = True) -> np.ndarray:
    """Reference points for the fidelity metrics: the object, without its sheet.

    The support plane is a large share of a dense cloud, and every candidate mesh
    deletes it on purpose. Leaving those points in would cap coverage at whatever
    fraction of the cloud is not table, and that fraction is not a quality signal.
    """
    pc = trimesh.load(fused, process=False)
    pts = np.asarray(pc.vertices)
    if len(pts) > limit:
        pts = pts[np.random.default_rng(seed).choice(len(pts), limit, replace=False)]
    if drop_plane and len(pts) > 100:
        n, d, tol = fit_plane(pts)
        signed = pts @ n - d
        above, below = (signed > tol).sum(), (signed < -tol).sum()
        keep = signed > tol if above > below else signed < -tol
        if keep.sum() > 0.05 * len(pts):
            pts = pts[keep]
    return pts


def metrics(m: trimesh.Trimesh, cloud: np.ndarray) -> dict:
    """How well the mesh agrees with the dense cloud it came from, plus the
    printability facts. The cloud is the only ground truth available, so both
    directions are measured: drift (mesh invents surface) and coverage (mesh
    loses surface). Smoothing and decimation trade one against the other.
    """
    scale = float(np.linalg.norm(cloud.max(0) - cloud.min(0)))
    # Sample the surface rather than using vertices: a vertex-based distance
    # rewards dense meshes for being dense, which would make the score push
    # face count up forever instead of judging the shape.
    surf, _ = trimesh.sample.sample_surface(m, min(400_000, max(50_000, len(m.faces) * 4)))
    tree = cKDTree(cloud)
    d_mesh, _ = tree.query(surf, workers=-1)       # mesh -> cloud: invented surface
    mtree = cKDTree(surf)
    d_cloud, _ = mtree.query(cloud, workers=-1)    # cloud -> mesh: lost surface
    v = np.asarray(m.vertices)
    eps = scale * 0.004
    return {
        "tunnels_lost": int(m.metadata.get("tunnels_lost", 0)),
        "faces": int(len(m.faces)),
        "vertices": int(len(v)),
        "watertight": bool(m.is_watertight),
        "components": int(m.body_count),
        "boundary_loops": len(boundary_loops(m)),
        "drift_rms_pct": round(float(np.sqrt((d_mesh ** 2).mean()) / scale * 100), 4),
        "drift_p95_pct": round(float(np.percentile(d_mesh, 95) / scale * 100), 4),
        "coverage": round(float((d_cloud < eps).mean()), 4),
        # Surface the photographs do not support. RMS drift barely moves when a
        # repair bridges a genuine through-hole -- the patch is small -- but the
        # patch is entirely invented, and this counts it.
        "unsupported": round(float((d_mesh > eps).mean()), 4),
        "extents": [round(float(x), 4) for x in m.extents],
        "volume": round(float(m.volume), 6) if m.is_watertight else None,
    }


def score(mt: dict) -> float:
    """One number to rank candidates by. Fidelity dominates; watertight is a hard
    requirement for printing so it is worth a large flat bonus; face count only
    breaks ties, because a mesh twice the size is not twice as good."""
    s = 0.0
    # Coverage as a straight reward, not a floor: candidates that all clear a
    # threshold would otherwise tie on it, and losing 1.4% of the object is a
    # real difference even when both meshes are "good enough".
    s += mt["coverage"] * 40.0
    s -= mt.get("unsupported", 0.0) * 45.0
    s -= mt["drift_rms_pct"] * 8.0
    s += 12.0 if mt["watertight"] else 0.0
    # A filled-in through-hole outweighs any watertightness bonus it bought.
    s -= mt.get("tunnels_lost", 0) * 7.0
    s -= (mt["components"] - 1) * 2.0
    s -= mt["boundary_loops"] * 0.6
    s -= max(0.0, mt["faces"] - 400_000) / 400_000
    return round(s, 3)


def evaluate(fused: Path, p: Params, cache: Path, cloud: np.ndarray | None = None) -> dict:
    t0 = time.time()
    m = build(fused, p, cache)
    if cloud is None:
        cloud = cloud_points(fused)
    mt = metrics(m, cloud)
    mt["seconds"] = round(time.time() - t0, 1)
    return {"params": asdict(p), "metrics": mt, "score": score(mt), "mesh": m}


if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("fused", type=Path)
    ap.add_argument("-o", "--out", type=Path)
    for f, t in (("poisson_depth", int), ("poisson_trim", float), ("plane_margin", float),
                 ("min_component_frac", float), ("smooth_iters", int),
                 ("target_faces", int), ("scale_to_mm", float)):
        ap.add_argument("--" + f.replace("_", "-"), type=t)
    ap.add_argument("--repair", choices=["none", "cap", "meshfix"])
    a = ap.parse_args()
    kw = {k: v for k, v in vars(a).items()
          if v is not None and k not in ("fused", "out")}
    p = replace(Params(), **kw)
    r = evaluate(a.fused, p, a.fused.parent / ".meshcache")
    print(json.dumps({"params": r["params"], "metrics": r["metrics"],
                      "score": r["score"]}, indent=1))
    if a.out:
        r["mesh"].export(a.out)
        print("->", a.out)
