#!/usr/bin/env bash
# Cut the Sri Lanka base map out of a Protomaps daily build.
#   data/basemap/build.sh [YYYYMMDD]      (default: today, UTC)
# Writes data/downloads/sri-lanka-YYYY-MM-DD.pmtiles (kept out of git).
set -euo pipefail

BUILD="${1:-$(date -u +%Y%m%d)}"
NAME="sri-lanka-${BUILD:0:4}-${BUILD:4:2}-${BUILD:6:2}.pmtiles"
BBOX="79.40,5.70,82.10,10.10" # Sri Lanka with a small margin: min lon, min lat, max lon, max lat
OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/downloads"
mkdir -p "$OUT_DIR"

# Git Bash on Windows needs a Windows path for the Docker volume and no path rewriting
if command -v cygpath >/dev/null; then OUT_MOUNT="$(cygpath -w "$OUT_DIR")"; export MSYS_NO_PATHCONV=1; else OUT_MOUNT="$OUT_DIR"; fi

docker run --rm -v "$OUT_MOUNT:/out" protomaps/go-pmtiles:v1.31.2 \
  extract "https://build.protomaps.com/${BUILD}.pmtiles" "/out/${NAME}" --bbox="$BBOX"

echo "Wrote $OUT_DIR/$NAME"
