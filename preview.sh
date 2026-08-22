#!/usr/bin/env bash
# Fuse whatever depth maps patch_match_stereo has finished so far into a
# viewable point cloud, without disturbing the run that is still going.
#
# The depth maps are hardlinked into a snapshot directory, so this costs no
# extra disk and cannot be corrupted by the writer. fusion.cfg is trimmed to the
# images that actually have a depth map -- fusion aborts on a missing one.
#
# Usage: ./preview.sh <project> [output_name]
set -euo pipefail

# Everything runs inside main() so bash parses the whole body up front.
# Bash otherwise reads a script incrementally by byte offset, and editing
# this file mid-run shifts those offsets -- the running job then resumes
# reading from the middle of a token and dies on a syntax error.
main() {

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COLMAP="$HERE/colmap.sh"
NAME="${1:?usage: preview.sh <project> [output_name]}"
OUT_NAME="${2:-preview-partial.ply}"

PROJ="$HERE/projects/$NAME"
DENSE="$PROJ/workspace/dense"
SNAP="$PROJ/workspace/preview"
[ -d "$DENSE/stereo/depth_maps" ] || { echo "ERROR: $NAME chưa tới bước dense" >&2; exit 1; }

# Geometric maps are the better source, but only exist once pass 2 has run.
if ls "$DENSE/stereo/depth_maps"/*.geometric.bin >/dev/null 2>&1; then
  TYPE=geometric
else
  TYPE=photometric
fi

rm -rf "$SNAP"; mkdir -p "$SNAP/stereo"
cp -al "$DENSE/images" "$SNAP/images"
cp -al "$DENSE/sparse" "$SNAP/sparse"
cp -al "$DENSE/stereo/depth_maps"  "$SNAP/stereo/depth_maps"
cp -al "$DENSE/stereo/normal_maps" "$SNAP/stereo/normal_maps"
cp -al "$DENSE/stereo/consistency_graphs" "$SNAP/stereo/consistency_graphs" 2>/dev/null \
  || mkdir -p "$SNAP/stereo/consistency_graphs"
cp "$DENSE/stereo/patch-match.cfg" "$SNAP/stereo/"
ls "$SNAP/stereo/depth_maps" | sed "s/\.$TYPE\.bin$//" | grep -v '\.bin$' | sort -u \
  > "$SNAP/stereo/fusion.cfg"

n=$(wc -l < "$SNAP/stereo/fusion.cfg")
total=$(wc -l < "$DENSE/stereo/fusion.cfg")
echo "==> fusing $n / $total images ($TYPE)"

to_container() { printf '/working/%s' "${1#"$HERE"/}"; }
"$COLMAP" stereo_fusion \
  --workspace_path "$(to_container "$SNAP")" \
  --workspace_format COLMAP \
  --input_type "$TYPE" \
  --output_path "$(to_container "$DENSE")/$OUT_NAME"

rm -rf "$SNAP"
echo "-> $DENSE/$OUT_NAME  ($(du -h "$DENSE/$OUT_NAME" | cut -f1))"
}

main "$@"
