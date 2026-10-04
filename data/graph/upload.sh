#!/usr/bin/env bash
# Package a routing graph built with `./gradlew buildGraph` and upload it to Cloudflare R2.
#   data/graph/upload.sh graph-YYYY-MM-DD [bucket]
# Needs Wrangler logged in (`npx wrangler login`) or CLOUDFLARE_API_TOKEN set.
# Graphs are named by their extract date and never overwritten; the API deploy downloads the one named in
# infra/azure/graph.env, so publish a new name and change that file instead.
set -euo pipefail

NAME="${1:?usage: upload.sh graph-YYYY-MM-DD [bucket]}"
BUCKET="${2:-routerank-tiles}"
DIR="$(cd "$(dirname "$0")" && pwd)/../downloads"
[ -d "$DIR/$NAME" ] || { echo "Not found: $DIR/$NAME (run ./gradlew buildGraph first)"; exit 1; }

tar -czf "$DIR/$NAME.tar.gz" -C "$DIR" "$NAME"
npx --prefer-offline -y wrangler@4 r2 object put "$BUCKET/graphs/$NAME.tar.gz" --file "$DIR/$NAME.tar.gz" --remote \
  --content-type application/gzip \
  --cache-control "public, max-age=31536000, immutable"

echo "Uploaded $BUCKET/graphs/$NAME.tar.gz. Point infra/azure/graph.env at it."
