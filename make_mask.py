#!/usr/bin/env python3
"""Build one COLMAP mask for a turntable capture.

A turntable rig breaks the assumption COLMAP is built on: it wants a static
scene and a moving camera, and it gets a moving scene and a static camera. The
features on the wall behind the table all agree that the camera never moved,
the features on the rotating mat all agree that it orbited, and the mapper has
to pick one. On a textured background it usually picks the wrong one.

The fix is to let it see only what rotates. And because the camera does not
move, "what rotates" is the same region of every frame -- so a single mask
covers the whole set, which is normally the expensive part of masking.

Finding that region needs no circle detection and no hand-drawing: sample
frames through the run, take the per-pixel standard deviation, and the turntable
lights up while the room stays flat. Everything after that is cleanup.

COLMAP's convention: masks/<image filename>.png, same dimensions as the photo,
black = ignore, white = keep.

Usage:
  ./.venv/bin/python make_mask.py projects/<name> [--frames 40] [--grow 12]
                                                  [--shape ellipse|blob]
"""
from __future__ import annotations

import argparse
import shutil
from pathlib import Path

import numpy as np
import piexif
from PIL import Image, ImageDraw
from scipy import ndimage

IMAGE_EXT = {".jpg", ".jpeg", ".png", ".tif", ".tiff", ".webp"}
WORK_WIDTH = 640          # the motion map needs no more resolution than this


def load_gray(path: Path, width: int) -> np.ndarray | None:
    try:
        im = Image.open(path)
        im.draft("L", (width, width))     # DCT-domain downscale: much faster
        im = im.convert("L")
    except Exception:
        return None
    if im.width != width:
        im = im.resize((width, round(im.height * width / im.width)), Image.BILINEAR)
    return np.asarray(im, dtype=np.float32)


def motion_map(files: list[Path], frames: int) -> np.ndarray:
    """Per-pixel standard deviation across frames spread over the whole run."""
    idx = np.linspace(0, len(files) - 1, min(frames, len(files))).round().astype(int)
    stack = []
    shape = None
    for i in sorted(set(idx.tolist())):
        g = load_gray(files[i], WORK_WIDTH)
        if g is None:
            continue
        if shape is None:
            shape = g.shape
        elif g.shape != shape:
            continue                       # a stray photo at another resolution
        stack.append(g)
    if len(stack) < 3:
        raise RuntimeError("cần ít nhất 3 ảnh đọc được")
    return np.stack(stack).std(axis=0)


def region_from_motion(std: np.ndarray, grow_px: int) -> np.ndarray:
    """Turn the motion map into one solid region.

    The threshold is relative to the frame's own noise floor rather than an
    absolute number: a dim room and a bright one produce very different
    standard deviations, but in both the turntable is far above the background.
    """
    quiet = np.percentile(std, 40)
    loud = np.percentile(std, 99)
    thr = quiet + 0.18 * (loud - quiet)
    mask = std > thr

    # Speckle in, gaps out: the mat's pattern makes the raw map lacy, and the
    # object's flat faces can go quiet for a few frames at a time.
    mask = ndimage.binary_closing(mask, np.ones((7, 7)))
    mask = ndimage.binary_opening(mask, np.ones((3, 3)))
    mask = ndimage.binary_fill_holes(mask)

    lab, n = ndimage.label(mask)
    if n == 0:
        raise RuntimeError("không tìm thấy vùng chuyển động nào")
    sizes = ndimage.sum(mask, lab, range(1, n + 1))
    mask = lab == (int(np.argmax(sizes)) + 1)

    if grow_px:
        mask = ndimage.binary_dilation(mask, np.ones((grow_px, grow_px)))
    return mask


def to_ellipse(mask: np.ndarray) -> np.ndarray:
    """Replace the blob with the ellipse that bounds it.

    A turntable seen from an angle is an ellipse, and a clean ellipse keeps the
    mat's outer rim -- which carries the ArUco markers -- instead of nibbling at
    it wherever a few frames happened to look still.
    """
    ys, xs = np.nonzero(mask)
    out = Image.new("L", (mask.shape[1], mask.shape[0]), 0)
    ImageDraw.Draw(out).ellipse([xs.min(), ys.min(), xs.max(), ys.max()], fill=255)
    return np.asarray(out) > 0


def scale_focal_prior(exif_bytes: bytes | None, ratio: float) -> bytes | None:
    """Keep the focal-length prior honest after a crop.

    COLMAP reads FocalLengthIn35mmFilm and turns it into pixels using the image
    width. Cropping narrows the effective sensor without changing the lens, so
    the 35 mm equivalent goes UP by exactly the crop ratio. Leaving the original
    value would hand the mapper a focal length that is wrong by that factor,
    which is a worse start than no prior at all.
    """
    if not exif_bytes:
        return None
    try:
        ex = piexif.load(exif_bytes)
    except Exception:
        return None
    exif = ex.get("Exif", {})
    tag = piexif.ExifIFD.FocalLengthIn35mmFilm
    if tag in exif:
        exif[tag] = max(1, int(round(exif[tag] * ratio)))
    for t in (piexif.ExifIFD.PixelXDimension, piexif.ExifIFD.PixelYDimension):
        exif.pop(t, None)          # rewritten by the encoder anyway
    ex["Exif"] = exif
    ex.pop("thumbnail", None)      # a thumbnail of the uncropped frame is a lie
    ex["1st"] = {}
    try:
        return piexif.dump(ex)
    except Exception:
        return None


def crop_all(files: list[Path], project: Path, mask_img: Image.Image,
             margin: float, blackout: bool) -> None:
    """Cut every frame down to the region worth reconstructing.

    Cropping is worth more than masking alone: the object goes from a quarter of
    the frame to most of it, so the same sensor spends its pixels on the subject
    instead of on the room. The crop is identical for every frame -- the camera
    never moves -- so one set of intrinsics still describes the whole set.
    """
    arr = np.asarray(mask_img) > 0
    ys, xs = np.nonzero(arr)
    w, h = mask_img.size
    mx, my = int(margin * w), int(margin * h)
    box = (max(0, int(xs.min()) - mx), max(0, int(ys.min()) - my),
           min(w, int(xs.max()) + mx), min(h, int(ys.max()) + my))
    cw, ch = box[2] - box[0], box[3] - box[1]
    # COLMAP turns the 35 mm equivalent into pixels with max(width, height), not
    # width, so the crop factor has to be measured on the same axis. A portrait
    # frame cropped mostly in height differs by more than a third between the
    # two -- 4096/2918 against 3072/3020 on this rig.
    ratio = max(w, h) / max(cw, ch)

    out_dir = project / "images_cropped"
    mask_dir = project / "masks_cropped"
    for d in (out_dir, mask_dir):
        if d.exists():
            shutil.rmtree(d)
        d.mkdir()

    cropped_mask = mask_img.crop(box)
    first_mask = mask_dir / (files[0].name + ".png")
    cropped_mask.save(first_mask, optimize=True)
    black = Image.new("RGB", cropped_mask.size, (0, 0, 0))

    for i, f in enumerate(files):
        with Image.open(f) as im:
            exif = scale_focal_prior(im.info.get("exif"), ratio)
            piece = im.convert("RGB").crop(box)
        if blackout:
            # A COLMAP mask only silences the feature extractor. Dense stereo
            # sweeps every pixel regardless, so without this the room behind the
            # table still gets reconstructed -- and a static room seen by a
            # camera COLMAP believes is orbiting turns into geometry that
            # swallows the actual subject. Flat black gives patch match nothing
            # to correlate, so those pixels drop out of the depth maps instead.
            piece = Image.composite(piece, black, cropped_mask.convert("L"))
        kwargs = {"quality": 95, "subsampling": 0}
        if exif:
            kwargs["exif"] = exif
        piece.save(out_dir / f.name, **kwargs)
        if i:
            target = mask_dir / (f.name + ".png")
            try:
                target.hardlink_to(first_mask)
            except OSError:
                shutil.copyfile(first_mask, target)

    print(f"-> {out_dir}/  cắt {w}x{h} -> {cw}x{ch} "
          f"(vật chiếm gấp {ratio:.1f} lần chiều ngang)")
    print(f"-> {mask_dir}/  mask khớp kích thước đã cắt")
    print(f"   tiêu cự 35mm trong EXIF đã nhân {ratio:.2f} cho khớp khung mới")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("project", type=Path)
    ap.add_argument("--frames", type=int, default=40,
                    help="số ảnh lấy mẫu để dựng bản đồ chuyển động")
    ap.add_argument("--grow", type=int, default=12,
                    help="nới rộng vùng giữ, tính bằng pixel ở độ phân giải 640")
    ap.add_argument("--shape", choices=["ellipse", "blob"], default="ellipse")
    ap.add_argument("--preview", type=Path, help="ghi ảnh chồng mask để mắt kiểm")
    ap.add_argument("--crop", action="store_true",
                    help="cắt luôn ảnh về quanh vùng giữ, ghi ra images_cropped/")
    ap.add_argument("--crop-margin", type=float, default=0.04,
                    help="nới thêm quanh vùng cắt, theo tỉ lệ cạnh")
    ap.add_argument("--keep-background", action="store_true",
                    help="giữ nguyên phần ngoài mask thay vì bôi đen")
    a = ap.parse_args()

    project = a.project.resolve()
    img_dir = project / "images"
    files = sorted(f for f in img_dir.iterdir()
                   if f.is_file() and f.suffix.lower() in IMAGE_EXT)
    if len(files) < 3:
        raise SystemExit(f"ERROR: {img_dir} có {len(files)} ảnh")

    std = motion_map(files, a.frames)
    small = region_from_motion(std, a.grow)
    if a.shape == "ellipse":
        small = to_ellipse(small)
    kept = small.mean()
    print(f"vùng giữ lại: {kept * 100:.1f}% khung hình")
    if kept > 0.9:
        print("  cảnh báo: gần như cả khung được giữ — nền có thể cũng đang đổi "
              "(ánh sáng nhấp nháy?), mask sẽ không giúp được gì")
    elif kept < 0.03:
        print("  cảnh báo: vùng giữ quá nhỏ, thử --grow lớn hơn hoặc --shape blob")

    with Image.open(files[0]) as probe:
        full_size = probe.size
    mask_img = Image.fromarray((small * 255).astype(np.uint8)).resize(
        full_size, Image.NEAREST)

    mask_dir = project / "masks"
    if mask_dir.exists():
        shutil.rmtree(mask_dir)
    mask_dir.mkdir()

    # One image on disk, then hard links: COLMAP wants a file per photo, but
    # every photo shares the same mask here and 115 copies of it would be waste.
    first = mask_dir / (files[0].name + ".png")
    mask_img.save(first, optimize=True)
    for f in files[1:]:
        target = mask_dir / (f.name + ".png")
        try:
            target.hardlink_to(first)
        except OSError:
            shutil.copyfile(first, target)

    print(f"-> {mask_dir}/  ({len(files)} mask, cùng trỏ về một file)")

    if a.crop:
        crop_all(files, project, mask_img, a.crop_margin, not a.keep_background)

    if a.preview:
        with Image.open(files[len(files) // 2]) as im:
            base = im.convert("RGB")
        shade = Image.new("RGB", base.size, (255, 60, 40))
        base = Image.composite(base, Image.blend(base, shade, 0.55),
                               mask_img.convert("L"))
        base.thumbnail((900, 900))
        base.save(a.preview, quality=85)
        print(f"-> {a.preview}")


if __name__ == "__main__":
    main()
