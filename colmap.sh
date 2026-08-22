#!/usr/bin/env bash
# Thin wrapper: run the COLMAP CUDA docker image against this project dir.
# Usage: ./colmap.sh <colmap-subcommand> [args...]
#   e.g. ./colmap.sh feature_extractor --help
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE="${COLMAP_IMAGE:-colmap/colmap:latest}"

exec docker run --rm --gpus all \
  --user "$(id -u):$(id -g)" \
  -v "$PROJECT_DIR:/working" \
  -w /working \
  -e CUDA_VISIBLE_DEVICES=0 \
  "$IMAGE" \
  colmap "$@"
