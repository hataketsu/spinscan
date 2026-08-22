#!/usr/bin/env bash
# Full COLMAP pipeline: photos taken around an object -> dense point cloud + mesh.
#
# Usage:
#   ./recon.sh                       # images/ -> workspace/
#   ./recon.sh <image_dir> <work_dir>
#
# Env overrides:
#   PRESET=fast|normal defaults for everything below (default: normal)
#   MATCHER=exhaustive|sequential|vocab_tree
#   MAX_SIZE=<px>      max image dimension for dense stereo
#   SINGLE_CAMERA=0|1  all photos from one camera/lens (default 1)
#   MESHER=poisson|delaunay|both
#   MASK_DIR=<path>    COLMAP masks, one PNG per photo (black = ignore)
#
# PRESET=fast trades surface detail for wall-clock: it reconstructs the shape
# and its deformations, not the texture on it. On 118 photos that is ~8 minutes
# instead of ~103, almost all of it won back from patch_match_stereo.
set -euo pipefail

# Everything runs inside main() so bash parses the whole body up front.
# Bash otherwise reads a script incrementally by byte offset, and editing
# this file mid-run shifts those offsets -- the running job then resumes
# reading from the middle of a token and dies on a syntax error.
main() {

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COLMAP="$HERE/colmap.sh"

IMAGE_DIR_HOST="${1:-$HERE/images}"
WORK_DIR_HOST="${2:-$HERE/workspace}"

# Paths as seen inside the container (project dir is mounted at /working).
to_container() { printf '/working/%s' "${1#"$HERE"/}"; }
IMAGES="$(to_container "$IMAGE_DIR_HOST")"
WORK="$(to_container "$WORK_DIR_HOST")"

# Masks keep the feature extractor off whatever is not the subject. On a
# turntable that is the whole ball game: the room behind the table says the
# camera never moved while the mat says it orbited, and the mapper cannot
# believe both.
MASK_DIR="${MASK_DIR:-}"
MASK_ARGS=()
if [ -n "$MASK_DIR" ]; then
  if [ ! -d "$MASK_DIR" ]; then
    echo "ERROR: MASK_DIR=$MASK_DIR không tồn tại" >&2
    exit 1
  fi
  MASK_ARGS=(--ImageReader.mask_path "$(to_container "$MASK_DIR")")
fi

PRESET="${PRESET:-normal}"
case "$PRESET" in
  fast)
    # patch_match_stereo cost is ~linear in pixel count and in iterations x
    # samples, and geometric consistency doubles it by re-running the whole
    # sweep. Shrinking the images is the single biggest lever: 2000 -> 700 px
    # is 8x fewer pixels, and shape survives it -- only fine relief does not.
    MATCHER="${MATCHER:-sequential}"
    MAX_SIZE="${MAX_SIZE:-700}"
    MESHER="${MESHER:-poisson}"
    FEATURE_SIZE=1600
    GEOM_CONSISTENCY=false
    PM_ITERS=3
    PM_SAMPLES=8
    PM_WINDOW_RADIUS=3
    # Photometric depth maps keep points no second view agrees with, so make
    # the per-map filter stricter to compensate for the missing geometric pass.
    PM_MIN_NCC=0.15
    FUSION_INPUT=photometric
    FUSION_MIN_PIXELS=4
    POISSON_DEPTH=9
    MAPPER_BA_ITERS=15
    ;;
  normal)
    MATCHER="${MATCHER:-exhaustive}"
    MAX_SIZE="${MAX_SIZE:-2000}"
    MESHER="${MESHER:-poisson}"
    FEATURE_SIZE=3200
    GEOM_CONSISTENCY=true
    PM_ITERS=5
    PM_SAMPLES=15
    PM_WINDOW_RADIUS=5
    PM_MIN_NCC=0.1
    FUSION_INPUT=geometric
    FUSION_MIN_PIXELS=5
    POISSON_DEPTH=13
    MAPPER_BA_ITERS=-1   # -1 = COLMAP default (solver decides)
    ;;
  *) echo "ERROR: unknown PRESET=$PRESET (fast|normal)" >&2; exit 1 ;;
esac
SINGLE_CAMERA="${SINGLE_CAMERA:-1}"

DB="$WORK/database.db"
STAGE_FILE="$WORK_DIR_HOST/.stage"

# stage() writes here from the very first step, so the tree has to exist first.
mkdir -p "$WORK_DIR_HOST/sparse" "$WORK_DIR_HOST/dense"

stage() {   # stage <n> <label>
  echo "$1|$2" > "$STAGE_FILE"
  echo "==> [$1/6] $2"
}

n_images=$(find "$IMAGE_DIR_HOST" -maxdepth 1 -type f \
  \( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' -o -iname '*.tif' -o -iname '*.tiff' \) | wc -l) || true
if [ -z "$n_images" ] || [ "$n_images" -lt 3 ]; then
  echo "ERROR: need at least 3 photos in $IMAGE_DIR_HOST (found $n_images)" >&2
  exit 1
fi
echo "==> $n_images images | preset=$PRESET | matcher=$MATCHER | max_size=$MAX_SIZE | mesher=$MESHER${MASK_DIR:+ | masks=on}"

# Phone EXIF (notably Xiaomi's NUL-filled ImageDescription) aborts the JPEG
# writer inside image_undistorter. Strip it down to the focal-length prior.
stage 0 "sanitize EXIF"
"$HERE/.venv/bin/python" "$HERE/sanitize_exif.py" "$IMAGE_DIR_HOST"

stage 1 "feature_extractor"
"$COLMAP" feature_extractor \
  --database_path "$DB" \
  --image_path "$IMAGES" \
  --ImageReader.single_camera "$SINGLE_CAMERA" \
  ${MASK_ARGS[@]+"${MASK_ARGS[@]}"} \
  --ImageReader.camera_model SIMPLE_RADIAL \
  --FeatureExtraction.use_gpu 1 \
  --FeatureExtraction.max_image_size "$FEATURE_SIZE"

stage 2 "${MATCHER}_matcher"
case "$MATCHER" in
  exhaustive)
    "$COLMAP" exhaustive_matcher --database_path "$DB" --FeatureMatching.use_gpu 1 ;;
  sequential)
    "$COLMAP" sequential_matcher --database_path "$DB" --FeatureMatching.use_gpu 1 \
      --SequentialMatching.overlap 15 --SequentialMatching.loop_detection 0 ;;
  vocab_tree)
    "$COLMAP" vocab_tree_matcher --database_path "$DB" --FeatureMatching.use_gpu 1 \
      --VocabTreeMatching.vocab_tree_path "$WORK/vocab_tree.bin" ;;
  *) echo "ERROR: unknown MATCHER=$MATCHER" >&2; exit 1 ;;
esac

stage 3 "mapper (sparse SfM)"
"$COLMAP" mapper \
  --database_path "$DB" \
  --image_path "$IMAGES" \
  --output_path "$WORK/sparse" \
  --Mapper.ba_local_max_num_iterations "$MAPPER_BA_ITERS" \
  --Mapper.ba_global_max_num_iterations "$MAPPER_BA_ITERS"

# The mapper writes one folder per disconnected model and does not order them by
# size, so pick the one with the most registered images (images.bin scales with it).
# `|| true`: with `set -e -o pipefail`, du failing on an unmatched glob would
# abort the script right here -- silently, with no ERROR line in the log, so the
# web UI reads the run as "done" with no outputs instead of "failed".
best_bin=$(du -b "$WORK_DIR_HOST"/sparse/*/images.bin 2>/dev/null | sort -rn | head -1 | cut -f2) || true
if [ -z "$best_bin" ]; then
  echo "ERROR: mapper produced no model. Photos likely lack overlap or texture." >&2
  exit 1
fi
MODEL_HOST="$(dirname "$best_bin")"
MODEL="$(to_container "$MODEL_HOST")"
n_models=$(find "$WORK_DIR_HOST/sparse" -mindepth 1 -maxdepth 1 -type d | wc -l)
echo "==> using model $(basename "$MODEL_HOST") of $n_models"
"$COLMAP" model_analyzer --path "$MODEL" || true

stage 4 "image_undistorter"
"$COLMAP" image_undistorter \
  --image_path "$IMAGES" \
  --input_path "$MODEL" \
  --output_path "$WORK/dense" \
  --output_type COLMAP \
  --max_image_size "$MAX_SIZE"

stage 5 "patch_match_stereo (CUDA, slow)"
"$COLMAP" patch_match_stereo \
  --workspace_path "$WORK/dense" \
  --workspace_format COLMAP \
  --PatchMatchStereo.geom_consistency "$GEOM_CONSISTENCY" \
  --PatchMatchStereo.num_iterations "$PM_ITERS" \
  --PatchMatchStereo.num_samples "$PM_SAMPLES" \
  --PatchMatchStereo.window_radius "$PM_WINDOW_RADIUS" \
  --PatchMatchStereo.filter_min_ncc "$PM_MIN_NCC"

stage 6 "stereo_fusion"
"$COLMAP" stereo_fusion \
  --workspace_path "$WORK/dense" \
  --workspace_format COLMAP \
  --input_type "$FUSION_INPUT" \
  --StereoFusion.min_num_pixels "$FUSION_MIN_PIXELS" \
  --output_path "$WORK/dense/fused.ply"

if [ "$MESHER" = "poisson" ] || [ "$MESHER" = "both" ]; then
  echo "==> mesh: poisson_mesher"
  "$COLMAP" poisson_mesher \
    --input_path "$WORK/dense/fused.ply" \
    --PoissonMeshing.depth "$POISSON_DEPTH" \
    --output_path "$WORK/dense/meshed-poisson.ply"
fi
if [ "$MESHER" = "delaunay" ] || [ "$MESHER" = "both" ]; then
  echo "==> mesh: delaunay_mesher"
  "$COLMAP" delaunay_mesher \
    --input_path "$WORK/dense" \
    --output_path "$WORK/dense/meshed-delaunay.ply"
fi

echo
echo "done|" > "$STAGE_FILE"
echo "DONE. Outputs in $WORK_DIR_HOST/dense/:"
ls -lh "$WORK_DIR_HOST/dense/"*.ply 2>/dev/null || true
}

main "$@"
