#!/usr/bin/env python3
"""Write dist/turntable-fw.json next to the two slot images.

The updater has to know a slot image's exact length and CRC-32 before it starts
streaming: the board checksums the whole thing in RAM and refuses to erase
anything unless it matches.
"""
import hashlib
import json
import re
import sys
import zlib
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
DIST = HERE.parent.parent / "dist"


def describe(path: Path) -> dict:
    data = path.read_bytes()
    return {
        "size": len(data),
        "crc32": zlib.crc32(data),
        "sha256": hashlib.sha256(data).hexdigest(),
    }


def version() -> str:
    """Whatever config.h calls this build, so the phone can show something."""
    text = (HERE / "config.h").read_text()
    m = re.search(r"FW_VERSION\s+\"([^\"]+)\"", text)
    return m.group(1) if m else "1.0"


def main() -> None:
    a, b = Path(sys.argv[1]), Path(sys.argv[2])
    DIST.mkdir(exist_ok=True)
    manifest = {
        "version": version(),
        "built": date.today().isoformat(),
        "slots": {"a": describe(a), "b": describe(b)},
    }
    for src, name in ((a, "turntable-a.bin"), (b, "turntable-b.bin")):
        (DIST / name).write_bytes(src.read_bytes())
    (DIST / "turntable.bin").write_bytes(a.read_bytes())
    (DIST / "turntable-fw.json").write_text(json.dumps(manifest, indent=1))
    s = manifest["slots"]
    print(f"manifest: v{manifest['version']}  "
          f"a={s['a']['size']}B crc={s['a']['crc32']}  "
          f"b={s['b']['size']}B crc={s['b']['crc32']}")


if __name__ == "__main__":
    main()
