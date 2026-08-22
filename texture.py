#!/usr/bin/env python3
"""Bake the photographs onto a finished mesh as a UV texture atlas.

`colorize.py` puts colour on the vertices, and that caps colour detail at the
vertex count: 130.740 vertices means 130.740 colour samples, each one smeared
across the triangles that share it. A phone scanning app ships a coarse mesh
with a 4096x4096 texture -- sixteen million samples -- and its output looks
sharper even where its geometry is worse. Texture resolution is independent of
mesh resolution, and that is the whole of the difference.

The bake needs three things COLMAP already produced and nothing else:

  * the undistorted images and their poses, so a point on the mesh can be
    turned into a pixel;
  * a visibility test, so a point is only coloured by cameras that could
    actually see it. The dense stage already wrote a depth map per view, and a
    point is visible exactly when its projected depth agrees with the stored
    one. That is a texture lookup per sample. Ray casting the same question
    against a 261k-face mesh is the same answer, several orders of magnitude
    slower;
  * a way to disagree. Every visibility test leaks, and a leaked sample is a
    piece of some other surface pasted onto this one. Averaging spreads that
    ghost over the texel; a median throws it away as long as it stays a
    minority, which is what 240 overlapping views buy.

Usage:
  ./.venv/bin/python texture.py projects/<name> [--mesh <path>] [--size 4096] [--views 0]
  ./.venv/bin/python texture.py projects/<name> --compare      # + ảnh so sánh
"""
from __future__ import annotations

import argparse
import hashlib
import resource
import struct
import time
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import trimesh
from PIL import Image
from scipy import ndimage

# Samples held in RAM at once while a tile is being resolved. The bake is
# memory-bound on this one number: it buys the tile height, and the tile height
# is the only thing standing between a 4096 atlas x 240 views and 40 GB.
SAMPLE_BUDGET = 24_000_000


# --------------------------------------------------------------------------- #
# COLMAP binary model
# --------------------------------------------------------------------------- #
# Three structs out of a documented, stable format. pycolmap is a compiled
# dependency and a second copy of COLMAP for less code than this.

_MODEL_PARAMS = {0: 3, 1: 4}          # SIMPLE_PINHOLE, PINHOLE


def _unpack(f, fmt: str):
    return struct.unpack("<" + fmt, f.read(struct.calcsize("<" + fmt)))


def read_cameras(path: Path) -> dict[int, tuple]:
    cams = {}
    with open(path, "rb") as f:
        for _ in range(_unpack(f, "Q")[0]):
            cid, model, w, h = _unpack(f, "iiQQ")
            if model not in _MODEL_PARAMS:
                raise SystemExit(
                    f"ERROR: camera model {model} chưa hỗ trợ — texture.py cần "
                    f"mô hình đã khử méo trong dense/sparse (PINHOLE)")
            p = _unpack(f, f"{_MODEL_PARAMS[model]}d")
            fx, fy = (p[0], p[0]) if model == 0 else (p[0], p[1])
            cams[cid] = (int(w), int(h), fx, fy, p[-2], p[-1])
    return cams


def read_images(path: Path) -> dict[str, tuple]:
    out = {}
    with open(path, "rb") as f:
        for _ in range(_unpack(f, "Q")[0]):
            d = _unpack(f, "i7di")
            name = b""
            while (c := f.read(1)) != b"\x00":
                name += c
            # The 2D observations are almost the whole file (50 MB here) and
            # nothing downstream needs them: 24 bytes each, seek past.
            f.seek(24 * _unpack(f, "Q")[0], 1)
            out[name.decode()] = (np.array(d[1:5]), np.array(d[5:8]), d[8])
    return out


def qvec2rotmat(q: np.ndarray) -> np.ndarray:
    w, x, y, z = q
    return np.array([
        [1 - 2*y*y - 2*z*z, 2*x*y - 2*z*w,     2*x*z + 2*y*w],
        [2*x*y + 2*z*w,     1 - 2*x*x - 2*z*z, 2*y*z - 2*x*w],
        [2*x*z - 2*y*w,     2*y*z + 2*x*w,     1 - 2*x*x - 2*y*y]])


def read_depth(path: Path) -> np.ndarray:
    """COLMAP depth map: ASCII `width&height&channels&` then row-major float32."""
    with open(path, "rb") as f:
        head = f.read(48)
        i = head.index(b"&")
        j = head.index(b"&", i + 1)
        k = head.index(b"&", j + 1)
        w, h, c = int(head[:i]), int(head[i+1:j]), int(head[j+1:k])
        f.seek(k + 1)
        a = np.frombuffer(f.read(w * h * c * 4), np.float32).reshape(h, w, c)
    return np.ascontiguousarray(a[..., 0])


@dataclass
class View:
    name: str
    R: np.ndarray          # world -> camera
    t: np.ndarray
    C: np.ndarray          # camera centre in world
    fx: float
    fy: float
    cx: float
    cy: float
    width: int
    height: int
    image: Path
    depth: Path


def load_views(dense: Path, limit: int) -> list[View]:
    cams = read_cameras(dense / "sparse" / "cameras.bin")
    poses = read_images(dense / "sparse" / "images.bin")
    names = sorted(poses)
    if limit > 0 and limit < len(names):
        # Evenly through the capture, not the first N: the first N is one arc of
        # the orbit and half the object would never face a camera.
        names = [names[i] for i in np.linspace(0, len(names) - 1, limit).astype(int)]
    views = []
    for n in names:
        q, t, cid = poses[n]
        img = dense / "images" / n
        dep = dense / "stereo" / "depth_maps" / (n + ".photometric.bin")
        if not (img.exists() and dep.exists()):
            continue
        R = qvec2rotmat(q)
        w, h, fx, fy, cx, cy = cams[cid]
        views.append(View(n, R.astype(np.float32), t.astype(np.float32),
                          (-R.T @ t).astype(np.float32), fx, fy, cx, cy, w, h,
                          img, dep))
    return views


class ViewData:
    """Decoded image + depth + a validity mask, kept under a RAM budget.

    The atlas is baked tile by tile and every tile walks every view, so without
    a cache each photograph is decoded once per tile. With one it is decoded
    once, as long as the capture fits the budget.
    """

    def __init__(self, budget_bytes: int = 3 << 30):
        self.budget = budget_bytes
        self.used = 0
        self.store: dict[str, tuple] = {}

    def get(self, v: View):
        hit = self.store.get(v.name)
        if hit is not None:
            return hit
        img = np.asarray(Image.open(v.image).convert("RGB"))
        dep = read_depth(v.depth)
        # The capture app masks the background to black, and JPEG leaves a dark
        # ringing halo a pixel or two wide along that edge. Eroding the
        # non-black region means neither the black nor its halo can be sampled,
        # including through the bilinear tap.
        ok = ndimage.binary_erosion(img.max(axis=2) > 8, np.ones((3, 3), bool),
                                    iterations=2)
        rec = (img, dep, ok)
        cost = img.nbytes + dep.nbytes + ok.nbytes
        if self.used + cost <= self.budget:
            self.store[v.name] = rec
            self.used += cost
        return rec


# --------------------------------------------------------------------------- #
# unwrap + atlas rasterisation
# --------------------------------------------------------------------------- #

def unwrap(mesh: trimesh.Trimesh, size: int, padding: int, cache: Path, tag: str):
    """xatlas UV unwrap. Vertices multiply because seams cut them apart.

    Cached: the unwrap is deterministic, costs ~2.5 min on a 261k-face mesh, and
    depends on nothing that changes between bakes.
    """
    cache.mkdir(parents=True, exist_ok=True)
    f = cache / f"unwrap-{tag}.npz"
    if f.exists():
        d = np.load(f)
        return d["vmapping"], d["indices"], d["uvs"]
    import xatlas
    at = xatlas.Atlas()
    at.add_mesh(np.asarray(mesh.vertices, np.float32),
                np.asarray(mesh.faces, np.uint32))
    po = xatlas.PackOptions()
    # Pack against the atlas that will actually be baked, so `padding` means
    # padding in final texels rather than in whatever size xatlas felt like.
    po.resolution = size
    po.padding = padding
    po.bruteForce = False
    at.generate(chart_options=xatlas.ChartOptions(), pack_options=po)
    vmapping, indices, uvs = at[0]
    np.savez_compressed(f, vmapping=vmapping, indices=indices, uvs=uvs)
    return vmapping, indices, uvs


def raster_chunks(tri_px: np.ndarray, W: int, H: int, budget: int = 8_000_000):
    """Triangle rasteriser, vectorised over triangles rather than over pixels.

    Triangles are bucketed by bounding-box side, so a whole bucket shares one
    candidate grid and the inside test runs on millions of candidates at once. A
    Python loop over 261k triangles costs more than the entire bake does.

    Yields (triangle index, flat pixel index, barycentric weights) per chunk.
    """
    lo = np.floor(tri_px.min(axis=1)).astype(np.int32)
    hi = np.ceil(tri_px.max(axis=1)).astype(np.int32)
    side = np.maximum((hi - lo).max(axis=1), 1)
    bucket = 1 << np.ceil(np.log2(side)).astype(np.int32)

    a, b, c = tri_px[:, 0], tri_px[:, 1], tri_px[:, 2]
    e0, e1 = b - a, c - a
    den = e0[:, 0] * e1[:, 1] - e1[:, 0] * e0[:, 1]
    den = np.where(np.abs(den) < 1e-12, 1e-12, den)

    for s in np.unique(bucket):
        tris = np.flatnonzero(bucket == s)
        step = max(1, budget // int(s * s))
        g = np.arange(s, dtype=np.int32)
        gx, gy = np.meshgrid(g, g)
        gx, gy = gx.ravel(), gy.ravel()
        for i in range(0, len(tris), step):
            t = tris[i:i + step]
            X = lo[t, 0, None] + gx[None, :]
            Y = lo[t, 1, None] + gy[None, :]
            ok = (X >= 0) & (X < W) & (Y >= 0) & (Y < H)
            dx = X + 0.5 - a[t, 0, None]
            dy = Y + 0.5 - a[t, 1, None]
            w1 = (dx * e1[t, 1, None] - dy * e1[t, 0, None]) / den[t, None]
            w2 = (e0[t, 0, None] * dy - e0[t, 1, None] * dx) / den[t, None]
            w0 = 1.0 - w1 - w2
            ok &= (w0 >= 0) & (w1 >= 0) & (w2 >= 0)
            if not ok.any():
                continue
            r, cc = np.nonzero(ok)
            yield (t[r], Y[r, cc] * W + X[r, cc],
                   np.stack([w0[r, cc], w1[r, cc], w2[r, cc]], axis=1))


def rasterize_atlas(uvs: np.ndarray, faces: np.ndarray, size: int):
    """Which triangle owns each texel, and where inside it the texel centre is."""
    px = np.empty_like(uvs, dtype=np.float64)
    px[:, 0] = uvs[:, 0] * size
    px[:, 1] = (1.0 - uvs[:, 1]) * size          # OBJ v runs up, image rows run down
    tri = np.full(size * size, -1, np.int32)
    bary = np.zeros((size * size, 3), np.float32)
    tri_px = px[faces]
    for t, flat, w in raster_chunks(tri_px, size, size):
        tri[flat] = t
        bary[flat] = w
    # A triangle smaller than a texel can straddle four texel centres and own
    # none of them. Give it the texel its centroid lands in, if that texel is
    # still free -- otherwise it contributes nothing and its pixels come from a
    # neighbour, which is what a sub-texel triangle deserves.
    seen = np.zeros(len(faces), bool)
    seen[tri[tri >= 0]] = True
    miss = np.flatnonzero(~seen)
    if len(miss):
        cen = tri_px[miss].mean(axis=1)
        fx = np.clip(cen[:, 0].astype(np.int64), 0, size - 1)
        fy = np.clip(cen[:, 1].astype(np.int64), 0, size - 1)
        flat = fy * size + fx
        free = tri[flat] < 0
        tri[flat[free]] = miss[free]
        bary[flat[free]] = 1.0 / 3.0
    return tri, bary


# --------------------------------------------------------------------------- #
# projection + visibility
# --------------------------------------------------------------------------- #

def _bilinear(img: np.ndarray, u: np.ndarray, v: np.ndarray) -> np.ndarray:
    h, w = img.shape[:2]
    fu, fv = u - 0.5, v - 0.5
    x0 = np.clip(np.floor(fu), 0, w - 2).astype(np.int32)
    y0 = np.clip(np.floor(fv), 0, h - 2).astype(np.int32)
    ax = (fu - x0)[:, None].astype(np.float32)
    ay = (fv - y0)[:, None].astype(np.float32)
    x1, y1 = x0 + 1, y0 + 1
    return (img[y0, x0] * ((1 - ax) * (1 - ay)) + img[y0, x1] * (ax * (1 - ay))
            + img[y1, x0] * ((1 - ax) * ay) + img[y1, x1] * (ax * ay))


def sample_view(v: View, data: tuple, pos: np.ndarray, nrm: np.ndarray,
                tol: float, cos_min: float):
    """Colour every texel this view can honestly see. Returns (idx, rgb, weight)."""
    img, dep, okmask = data
    ih, iw = img.shape[:2]
    dh, dw = dep.shape

    cam = pos @ v.R.T + v.t
    z = cam[:, 2]
    d = v.C - pos
    # Unnormalised cosine first: the reject is the same and it saves a square
    # root on every texel of the atlas, most of which face the other way.
    dn = np.einsum("ij,ij->i", nrm, d)
    idx = np.flatnonzero((z > 1e-6) & (dn > 0))
    if not len(idx):
        return None
    zc = z[idx]
    u = v.fx * cam[idx, 0] / zc + v.cx
    w = v.fy * cam[idx, 1] / zc + v.cy

    keep = (u >= 1) & (u <= iw - 2) & (w >= 1) & (w <= ih - 2)
    idx, u, w, zc = idx[keep], u[keep], w[keep], zc[keep]
    if not len(idx):
        return None

    xi = np.clip((u * (dw / iw)).astype(np.int32), 0, dw - 1)
    yi = np.clip((w * (dh / ih)).astype(np.int32), 0, dh - 1)
    # Nearest, never interpolated: a depth map is a step function across an
    # occlusion edge and the average of foreground and background is a depth
    # that belongs to neither.
    dz = dep[yi, xi]
    keep = (dz > 0) & (np.abs(zc - dz) < tol * zc) & okmask[yi, xi]
    idx, u, w = idx[keep], u[keep], w[keep]
    if not len(idx):
        return None

    cos = dn[idx] / np.linalg.norm(d[idx], axis=1)
    # A view this oblique sees the texel as a sliver: one source pixel smeared
    # over many texels, and every pose error amplified along the surface.
    keep = cos > cos_min
    idx, u, w, cos = idx[keep], u[keep], w[keep], cos[keep]
    if not len(idx):
        return None
    r2 = (((u - v.cx) / (0.5 * iw)) ** 2 + ((w - v.cy) / (0.5 * ih)) ** 2)
    # Straight-on beats oblique (an oblique view spreads one pixel over many
    # texels), and the centre of the frame beats the corner (lens softness and
    # residual pose error both grow outwards).
    weight = (np.minimum(cos, 1.0) ** 2 / (1.0 + r2)).astype(np.float32)
    rgb = np.clip(_bilinear(img, u, w), 0, 255).astype(np.uint8)
    return idx.astype(np.int32), rgb, weight


def weighted_median(idx: np.ndarray, rgb: np.ndarray, w: np.ndarray, n: int,
                    best_frac: float = 0.0):
    """Per-texel weighted median of the samples, grouped by texel id.

    A mean is what a texturing pipeline reaches for first and it is the wrong
    tool: every visibility test leaks a few samples of the wrong surface, and a
    mean mixes each of them in at its full weight, so one bad view leaves a
    visible ghost across the whole area it leaked into. A median ignores any
    minority no matter how far off it is -- with 240 overlapping views the
    honest samples are always the majority.

    Samples are ordered by luminance and the median one is taken whole, rather
    than taking a median per channel: the result is then a colour some camera
    actually recorded, not three channels borrowed from three different views.
    """
    lum = (rgb.astype(np.int64) @ np.array([77, 150, 29], np.int64)) >> 8
    # One key, one sort: texel id in the high bits, luminance in the low eight,
    # so the samples come out grouped by texel and ordered inside each group.
    order = np.argsort(idx.astype(np.int64) * 256 + lum)
    si, sw = idx[order], w[order].astype(np.float64)
    counts = np.bincount(si, minlength=n)
    start = np.concatenate([[0], np.cumsum(counts)])
    used = np.flatnonzero(counts)
    s, e = start[used], start[used + 1]
    if best_frac > 0:
        # A texel here is seen by ~85 cameras and most of them see it badly. A
        # median over all of them is robust and soft; a median over only the
        # views that see it nearly straight-on is just as robust -- half of 85
        # is still a crowd -- and every sample in it is a sharper photograph.
        # Dropped samples keep their place and lose their weight, which is the
        # same thing as not being there.
        thr = np.repeat(np.maximum.reduceat(sw, s) * best_frac, counts[used])
        sw = np.where(sw >= thr, sw, 0.0)
    cum = np.cumsum(sw)
    base = np.where(s > 0, cum[np.maximum(s - 1, 0)], 0.0)
    # searchsorted on the global running sum picks, inside each group, the first
    # sample past half of that group's weight.
    pick = np.searchsorted(cum, base + 0.5 * (cum[e - 1] - base), side="left")
    pick = np.clip(pick, s, e - 1)
    out = np.zeros((n, 3), np.uint8)
    out[used] = rgb[order[pick]]
    return out, counts


# --------------------------------------------------------------------------- #
# bake
# --------------------------------------------------------------------------- #

def bake(views, cache: ViewData, pos, nrm, tex, size, tol, cos_min,
         best_frac=0.0, log=print):
    """Stream every view over the atlas, one horizontal band at a time.

    Nothing here holds a samples-per-texel array for the whole atlas: at 4096
    and 240 views that is tens of gigabytes. A band is small enough that all of
    its samples fit at once, which is what makes an exact median affordable.
    """
    n = len(pos)
    # One cheap pass over a random handful of texels says how many views see an
    # average texel, and therefore how tall a band can be.
    probe = np.random.default_rng(0).choice(n, min(20_000, n), replace=False)
    seen = 0
    for v in views:
        r = sample_view(v, cache.get(v), pos[probe], nrm[probe], tol, cos_min)
        seen += 0 if r is None else len(r[0])
    per_texel = max(1.0, seen / len(probe))
    rows = int(np.clip(SAMPLE_BUDGET / (per_texel * size), 1, size))
    log(f"  ~{per_texel:.0f} góc nhìn/texel -> chia atlas thành "
        f"{int(np.ceil(size / rows))} dải {rows} hàng")

    rgb_out = np.zeros((n, 3), np.uint8)
    counts = np.zeros(n, np.int32)
    contrib = np.zeros(len(views), np.int64)
    t0 = time.time()
    for band, y0 in enumerate(range(0, size, rows)):
        y1 = min(y0 + rows, size)
        a = int(np.searchsorted(tex, y0 * size))
        b = int(np.searchsorted(tex, y1 * size))
        if b <= a:
            continue
        P, N = pos[a:b], nrm[a:b]
        pi, pr, pw = [], [], []
        for k, v in enumerate(views):
            r = sample_view(v, cache.get(v), P, N, tol, cos_min)
            if r is None:
                continue
            contrib[k] += len(r[0])
            pi.append(r[0]); pr.append(r[1]); pw.append(r[2])
        if not pi:
            continue
        m, c = weighted_median(np.concatenate(pi), np.concatenate(pr),
                               np.concatenate(pw), b - a, best_frac)
        rgb_out[a:b], counts[a:b] = m, c
        log(f"  dải {band + 1}: {b - a} texel, {sum(len(x) for x in pi)/1e6:.1f}M mẫu"
            f"  [{time.time() - t0:.0f}s]")
    return rgb_out, counts, contrib


def fill_and_dilate(atlas: np.ndarray, valid: np.ndarray, pad: int):
    """Give every texel a colour, twice over.

    Two problems. Texels no camera saw are black holes in the middle of a
    chart. And texels just outside a chart get pulled in by the renderer's
    bilinear filter at seam edges, so a chart that stops exactly at its own
    edge is fringed with background at render time.

    The skirt is grown by averaging, a texel at a time, because a seam is where
    two charts of the same surface meet and a soft edge hides the join. The
    holes are then closed in one shot by nearest-valid-texel, because a hole
    can be hundreds of texels across -- the underside of the board is never
    photographed at all -- and averaging its way inwards would take hundreds of
    passes over the whole atlas to reach the middle.
    """
    img = atlas.astype(np.float32)
    v = valid.astype(np.float32)
    for _ in range(max(pad, 1)):
        den = ndimage.uniform_filter(v, 3, mode="constant")
        grow = (den > 0) & (v == 0)
        if not grow.any():
            break
        num = np.stack([ndimage.uniform_filter(img[..., c] * v, 3, mode="constant")
                        for c in range(3)], axis=-1)
        img[grow] = num[grow] / den[grow, None]
        v[grow] = 1.0
    hole = v == 0
    if hole.any():
        # distance_transform_edt measures to the nearest zero, so the mask is
        # inverted: every empty texel gets the index of the nearest filled one.
        _, (yi, xi) = ndimage.distance_transform_edt(hole, return_indices=True)
        img[hole] = img[yi[hole], xi[hole]]
    return np.clip(img, 0, 255).astype(np.uint8)


# --------------------------------------------------------------------------- #
# output
# --------------------------------------------------------------------------- #

def write_obj(path: Path, V, F, uv, VN, png_name: str):
    mtl = path.with_suffix(".mtl")
    mtl.write_text("newmtl atlas\nKa 1.000 1.000 1.000\nKd 1.000 1.000 1.000\n"
                   f"Ks 0.000 0.000 0.000\nd 1.0\nillum 1\nmap_Kd {png_name}\n")
    with open(path, "w") as f:
        f.write(f"mtllib {mtl.name}\no textured\n")
        np.savetxt(f, V, fmt="v %.6f %.6f %.6f")
        np.savetxt(f, uv, fmt="vt %.6f %.6f")
        np.savetxt(f, VN, fmt="vn %.4f %.4f %.4f")
        f.write("usemtl atlas\n")
        # Vertex, uv and normal share one index because the unwrap already split
        # every vertex that needed splitting.
        i = (F + 1).astype(np.int64)
        np.savetxt(f, np.repeat(i, 3, axis=1),
                   fmt="f %d/%d/%d %d/%d/%d %d/%d/%d")


# --------------------------------------------------------------------------- #
# comparison render (software, because there is no GL in this venv and the GPU
# is busy). Same rasteriser as the atlas, plus a z-buffer.
# --------------------------------------------------------------------------- #



def render_mesh(v: View, W: int, H: int, V, F, VN, colours=None,
                uv=None, atlas=None):
    """Flat software render of a mesh through a real capture camera."""
    sx, sy = W / (2 * v.cx), H / (2 * v.cy)
    fx, fy, cx, cy = v.fx * sx, v.fy * sy, v.cx * sx, v.cy * sy
    cam = V @ v.R.T + v.t
    z = cam[:, 2]
    zs = np.where(np.abs(z) < 1e-6, 1e-6, z)
    px = np.stack([fx * cam[:, 0] / zs + cx, fy * cam[:, 1] / zs + cy], axis=1)
    keep = np.flatnonzero((z[F] > 1e-6).all(axis=1))
    tri_px, tri_z = px[F[keep]], z[F[keep]]

    pix, dep, tid, bw = [], [], [], []
    for t, flat, w in raster_chunks(tri_px, W, H):
        inv = w / tri_z[t]
        s = inv.sum(axis=1)
        zz = 1.0 / s
        pix.append(flat); dep.append(zz); tid.append(keep[t])
        bw.append(inv * zz[:, None])          # perspective-correct weights
    out = np.zeros((H * W, 3), np.uint8)
    if not pix:
        return out.reshape(H, W, 3)
    pix = np.concatenate(pix); dep = np.concatenate(dep)
    tid = np.concatenate(tid); bw = np.concatenate(bw)
    # z-buffer without ufunc.at: sort by (pixel, depth) and keep the first row
    # of each pixel's run.
    order = np.lexsort((dep, pix))
    p = pix[order]
    first = np.empty(len(p), bool)
    first[0] = True
    first[1:] = p[1:] != p[:-1]
    sel = order[first]
    fsel, bsel = F[tid[sel]], bw[sel]

    if uv is not None:
        t = (uv[fsel] * bsel[:, :, None]).sum(axis=1)
        ah, aw = atlas.shape[:2]
        col = _bilinear(atlas,
                        np.clip(t[:, 0] * aw, 0.5, aw - 0.5),
                        np.clip((1 - t[:, 1]) * ah, 0.5, ah - 0.5))
    else:
        col = (colours[fsel].astype(np.float32) * bsel[:, :, None]).sum(axis=1)

    n = (VN[fsel] * bsel[:, :, None]).sum(axis=1)
    d = v.C - (V[fsel] * bsel[:, :, None]).sum(axis=1)
    lam = np.abs(np.einsum("ij,ij->i", n, d)
                 / (np.linalg.norm(n, axis=1) * np.linalg.norm(d, axis=1) + 1e-9))
    # Identical headlight shading on both meshes, so the comparison is about
    # colour detail and not about who got the nicer lighting.
    shade = (0.35 + 0.65 * lam)[:, None]
    out[p[first]] = np.clip(col * shade, 0, 255).astype(np.uint8)
    return out.reshape(H, W, 3)


_FONTS = ("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
          "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
          "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf")


def _label_font(px: int):
    # PIL's built-in bitmap font has no Vietnamese diacritics: it draws them as
    # empty boxes, which is worse than leaving the labels off.
    from PIL import ImageFont
    for f in _FONTS:
        if Path(f).exists():
            return ImageFont.truetype(f, px)
    return ImageFont.load_default()


def comparison(dense: Path, views, obj_V, obj_F, obj_uv, obj_VN, atlas,
               out: Path, n_views: int = 3, scale: float = 2.0):
    from PIL import ImageDraw
    cpath = dense / "auto-best-color.ply"
    if not cpath.exists():
        print(f"  bỏ qua ảnh so sánh: không thấy {cpath}")
        return None
    cm = trimesh.load(cpath, process=False)
    cm.metadata.clear()
    cV = np.asarray(cm.vertices, np.float32)
    cF = np.asarray(cm.faces)
    cC = np.asarray(cm.visual.vertex_colors)[:, :3].astype(np.float32)
    cN = np.asarray(cm.vertex_normals, np.float32)

    picks = [views[i] for i in np.linspace(0, len(views) - 1, n_views + 2)
             .astype(int)[1:-1]]
    rows = []
    for v in picks:
        photo = np.asarray(Image.open(v.image).convert("RGB"))
        H0, W0 = photo.shape[:2]
        W, H = int(W0 * scale), int(H0 * scale)
        a = render_mesh(v, W, H, cV, cF, cN, colours=cC)
        b = render_mesh(v, W, H, obj_V, obj_F, obj_VN, uv=obj_uv, atlas=atlas)
        # Crop all three to the object, so the comparison spends its pixels on
        # the thing being compared.
        m = np.flatnonzero((a.max(axis=2) > 0) | (b.max(axis=2) > 0))
        ys, xs = np.divmod(m, W)
        y0, y1 = max(0, ys.min() - 8), min(H, ys.max() + 9)
        x0, x1 = max(0, xs.min() - 8), min(W, xs.max() + 9)
        ph = np.asarray(Image.fromarray(photo).resize((W, H), Image.LANCZOS))
        rows.append([ph[y0:y1, x0:x1], a[y0:y1, x0:x1], b[y0:y1, x0:x1]])

    ch = max(r[0].shape[0] for r in rows)
    cw = max(r[0].shape[1] for r in rows)
    pad, top = 8, 40
    canvas = Image.new("RGB", (3 * cw + 4 * pad, len(rows) * (ch + pad) + top + pad),
                       (16, 16, 16))
    for ri, row in enumerate(rows):
        for ci, im in enumerate(row):
            # Rows crop to different sizes; centre them so the three columns of
            # one row stay registered against each other.
            canvas.paste(Image.fromarray(im),
                         (pad + ci * (cw + pad) + (cw - im.shape[1]) // 2,
                          top + ri * (ch + pad) + (ch - im.shape[0]) // 2))
    d = ImageDraw.Draw(canvas)
    font = _label_font(22)
    for ci, lab in enumerate((f"ảnh gốc (phóng {scale:g}x)",
                              "màu theo đỉnh", "texture nướng")):
        d.text((pad + ci * (cw + pad) + 4, 10), lab, fill=(235, 235, 235), font=font)
    canvas.save(out)
    return out


# --------------------------------------------------------------------------- #

def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("target", type=Path, help="thư mục project")
    ap.add_argument("--mesh", type=Path, help="mesh nguồn (mặc định auto-best.stl)")
    ap.add_argument("--size", type=int, default=4096, help="cạnh atlas, texel")
    ap.add_argument("--views", type=int, default=0, help="0 = dùng hết ảnh")
    ap.add_argument("--depth-tol", type=float, default=0.01,
                    help="sai lệch độ sâu tương đối còn coi là nhìn thấy")
    ap.add_argument("--min-cos", type=float, default=0.15,
                    help="cos góc tối thiểu giữa pháp tuyến và hướng nhìn")
    ap.add_argument("--best-frac", type=float, default=0.5,
                    help="chỉ lấy trung vị trên các mẫu có trọng số >= tỉ lệ này "
                         "so với mẫu tốt nhất của texel (0 = lấy hết)")
    ap.add_argument("--padding", type=int, default=4, help="đệm giữa các mảnh UV")
    ap.add_argument("--compare", action="store_true", help="render ảnh so sánh")
    ap.add_argument("--compare-only", action="store_true",
                    help="chỉ render ảnh so sánh từ textured.obj đã có")
    a = ap.parse_args()

    dense = a.target / "workspace" / "dense"
    if not dense.exists():
        raise SystemExit(f"ERROR: không thấy {dense}")
    mesh_path = a.mesh or (dense / "auto-best.stl")
    if not mesh_path.exists():
        mesh_path = dense / "meshed-poisson.ply"
    if not mesh_path.exists():
        raise SystemExit(f"ERROR: không thấy mesh trong {dense}")
    out_obj = dense / "textured.obj"
    out_png = dense / "textured.png"
    t_start = time.time()

    mesh = trimesh.load(mesh_path, process=False)
    mesh.metadata.clear()
    # An STL stores three unshared corners per triangle; unwrapping that would
    # cut a seam along every single edge.
    before = len(mesh.vertices)
    mesh.merge_vertices()
    V0 = np.asarray(mesh.vertices, np.float32)
    N0 = np.asarray(mesh.vertex_normals, np.float32)
    print(f"{mesh_path.name}: {len(mesh.faces)} mặt, {len(V0)} đỉnh"
          + (f" (gộp {before - len(V0)} đỉnh trùng)" if before != len(V0) else ""))

    st = mesh_path.stat()
    tag = hashlib.sha1(f"{mesh_path}{st.st_mtime_ns}{st.st_size}"
                       f"{a.size}{a.padding}".encode()).hexdigest()[:10]
    t0 = time.time()
    vmap, F, uv = unwrap(mesh, a.size, a.padding, dense / ".texcache", tag)
    V = V0[vmap]
    VN = N0[vmap]
    print(f"  UV: {len(uv)} đỉnh sau khi cắt seam ({time.time() - t0:.0f}s)")

    views = load_views(dense, a.views)
    if not views:
        raise SystemExit("ERROR: không đọc được ảnh/pose/độ sâu trong dense/")

    if a.compare_only:
        atlas = np.asarray(Image.open(out_png).convert("RGB"))
        p = comparison(dense, views, V, F, uv, VN, atlas,
                       dense / "texture-compare.png")
        print(f"-> {p}")
        return

    t0 = time.time()
    tri, bary = rasterize_atlas(uv, F, a.size)
    tex = np.flatnonzero(tri >= 0).astype(np.int64)
    fv = F[tri[tex]]
    b = bary[tex]
    del bary, tri
    pos = sum(V[fv[:, k]] * b[:, k:k + 1] for k in range(3))
    nrm = sum(VN[fv[:, k]] * b[:, k:k + 1] for k in range(3))
    nrm /= np.linalg.norm(nrm, axis=1, keepdims=True) + 1e-12
    del fv, b
    print(f"  atlas {a.size}x{a.size}: {len(tex)} texel có mặt tam giác "
          f"({len(tex) / a.size ** 2 * 100:.1f}% diện tích) ({time.time() - t0:.0f}s)")

    print(f"  {len(views)} góc nhìn, sai số độ sâu cho phép {a.depth_tol * 100:.1f}%")
    cache = ViewData()
    t0 = time.time()
    rgb, counts, contrib = bake(views, cache, pos, nrm, tex, a.size,
                                a.depth_tol, a.min_cos, a.best_frac)
    t_bake = time.time() - t0

    atlas = np.zeros((a.size, a.size, 3), np.uint8)
    valid = np.zeros(a.size * a.size, bool)
    atlas.reshape(-1, 3)[tex] = rgb
    valid[tex] = counts > 0
    pad = max(2, a.size // 512)
    atlas = fill_and_dilate(atlas, valid.reshape(a.size, a.size), pad)
    Image.fromarray(atlas).save(out_png)
    write_obj(out_obj, V.astype(np.float64), F, uv, VN, out_png.name)

    hit = int((counts > 0).sum())
    med = int(np.median(counts[counts > 0])) if hit else 0
    peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1e6
    print(f"\nĐộ phủ texel: {hit / len(tex) * 100:.2f}% "
          f"({hit}/{len(tex)} texel có ít nhất một mẫu thật)")
    print(f"Số góc nhìn góp mặt cho mỗi texel, trung vị: {med}")
    print(f"Ảnh thực sự góp màu: {int((contrib > 0).sum())}/{len(views)}")
    print(f"Nướng {t_bake:.0f}s, tổng {time.time() - t_start:.0f}s, "
          f"RAM đỉnh {peak:.1f} GB")
    print(f"-> {out_obj}\n-> {out_obj.with_suffix('.mtl')}\n"
          f"-> {out_png}  ({out_png.stat().st_size / 1e6:.1f} MB)")

    if a.compare:
        p = comparison(dense, views, V, F, uv, VN, atlas,
                       dense / "texture-compare.png")
        if p:
            print(f"-> {p}")


if __name__ == "__main__":
    main()
