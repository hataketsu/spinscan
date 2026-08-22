"""Voxel-downsample a COLMAP fused.ply, keeping normals and colour.

Poisson meshing cost scales with the input, and a 5-million-point cloud of a
palm-sized object is far past the point where extra points add detail. One
averaged point per voxel keeps the surface and cuts the mesh down to something
a laptop can open.

Usage: downsample.py <in.ply> <out.ply> [--target N]
"""
import sys
import numpy as np


def read_ply(path):
    with open(path, "rb") as f:
        hdr = b""
        while b"end_header" not in hdr:
            line = f.readline()
            # readline() returns b"" forever at EOF: a truncated or non-PLY file
            # would spin here instead of failing.
            if not line:
                raise ValueError(f"{path}: no PLY end_header (truncated file?)")
            hdr += line
        txt = hdr.decode("ascii", "replace")
        n = int(next(l for l in txt.splitlines() if l.startswith("element vertex")).split()[-1])
        props = [l.split()[-1] for l in txt.splitlines() if l.startswith("property")]
        types = [l.split()[1] for l in txt.splitlines() if l.startswith("property")]
        fmt = {"float": "f4", "double": "f8", "uchar": "u1", "uint8": "u1", "int": "i4"}
        dt = np.dtype([(p, fmt[t]) for p, t in zip(props, types)])
        return np.frombuffer(f.read(n * dt.itemsize), dtype=dt), props, types


def write_ply(path, data, props, types):
    head = ("ply\nformat binary_little_endian 1.0\n"
            f"element vertex {len(data)}\n"
            + "".join(f"property {t} {p}\n" for p, t in zip(props, types))
            + "end_header\n")
    with open(path, "wb") as f:
        f.write(head.encode("ascii"))
        f.write(data.tobytes())


def main():
    src, dst = sys.argv[1], sys.argv[2]
    target = int(sys.argv[sys.argv.index("--target") + 1]) if "--target" in sys.argv else 800_000

    data, props, types = read_ply(src)
    xyz = np.stack([data["x"], data["y"], data["z"]], 1).astype(np.float64)
    print(f"in: {len(xyz):,} points")

    # Binary-search a voxel size that lands near the target count.
    diag = float(np.linalg.norm(xyz.max(0) - xyz.min(0)))
    lo, hi = diag / 4000, diag / 20
    for _ in range(18):
        v = (lo + hi) / 2
        keys = np.unique(np.floor((xyz - xyz.min(0)) / v).astype(np.int64), axis=0)
        if len(keys) > target:
            lo = v
        else:
            hi = v
        if abs(len(keys) - target) < target * 0.05:
            break

    idx = np.floor((xyz - xyz.min(0)) / v).astype(np.int64)
    _, inv = np.unique(idx, axis=0, return_inverse=True)
    order = np.argsort(inv, kind="stable")
    first = np.concatenate(([True], inv[order][1:] != inv[order][:-1]))
    keep = order[first]          # one representative point per voxel

    out = data[keep].copy()
    print(f"voxel {v:.5f} -> out: {len(out):,} points ({len(out)/len(xyz)*100:.1f}%)")
    write_ply(dst, out, props, types)


if __name__ == "__main__":
    main()
