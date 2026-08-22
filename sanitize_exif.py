"""Strip EXIF tags that crash COLMAP's JPEG writer, keeping the focal-length prior.

Xiaomi (and other Android) cameras write an ImageDescription made of NUL bytes.
OpenImageIO maps that tag to an IPTC caption on write, hits a null pointer, and
aborts image_undistorter with:
    iptc.cpp:167: encode_iptc_iim_one_tag: Assertion 'data != nullptr' failed.

Rewrite each JPEG's EXIF down to a whitelist. Pixel data is untouched — piexif
replaces the APP1 segment only, so this is lossless and safe to re-run.

Usage: sanitize_exif.py <image_dir>
"""
import sys
from pathlib import Path

import piexif

# Tags COLMAP actually reads for the camera prior, plus harmless orientation info.
KEEP_0TH = {
    piexif.ImageIFD.Make, piexif.ImageIFD.Model, piexif.ImageIFD.Orientation,
    piexif.ImageIFD.XResolution, piexif.ImageIFD.YResolution,
    piexif.ImageIFD.ResolutionUnit,
}
KEEP_EXIF = {
    piexif.ExifIFD.FocalLength, piexif.ExifIFD.FocalLengthIn35mmFilm,
    piexif.ExifIFD.PixelXDimension, piexif.ExifIFD.PixelYDimension,
    piexif.ExifIFD.FNumber, piexif.ExifIFD.ExposureTime, piexif.ExifIFD.ISOSpeedRatings,
}


def sanitize(path: Path) -> str:
    try:
        exif = piexif.load(str(path))
    except Exception as e:
        return f"skip ({e})"
    before = sum(len(exif.get(k) or {}) for k in ("0th", "Exif", "GPS", "1st"))
    clean = {
        "0th": {k: v for k, v in exif.get("0th", {}).items() if k in KEEP_0TH},
        "Exif": {k: v for k, v in exif.get("Exif", {}).items() if k in KEEP_EXIF},
        "GPS": {}, "1st": {}, "thumbnail": None,
    }
    after = len(clean["0th"]) + len(clean["Exif"])
    if before == after:
        return "already clean"
    piexif.insert(piexif.dump(clean), str(path))
    return f"{before} -> {after} tags"


def main() -> int:
    d = Path(sys.argv[1] if len(sys.argv) > 1 else "images")
    files = sorted(f for f in d.iterdir()
                   if f.suffix.lower() in {".jpg", ".jpeg"})
    if not files:
        print(f"no JPEGs in {d}")
        return 0
    changed = 0
    for f in files:
        result = sanitize(f)
        if "->" in result:
            changed += 1
    print(f"sanitized EXIF: {changed}/{len(files)} files rewritten")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
