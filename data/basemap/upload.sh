#!/usr/bin/env bash
# Upload a built base map to Cloudflare R2 and set the bucket's CORS rules.
#   data/basemap/upload.sh sri-lanka-YYYY-MM-DD.pmtiles [bucket]
# Needs Wrangler logged in (`npx wrangler login`) or CLOUDFLARE_API_TOKEN set.
# The file name carries its date, so it is cached as immutable; publish a new
# date and change VITE_TILES_URL instead of overwriting an existing file.
set -euo pipefail

NAME="${1:?usage: upload.sh sri-lanka-YYYY-MM-DD.pmtiles [bucket]}"
BUCKET="${2:-routerank-tiles}"
DIR="$(cd "$(dirname "$0")" && pwd)"
FILE="$DIR/../downloads/$NAME"
[ -f "$FILE" ] || { echo "Not found: $FILE (run build.sh first)"; exit 1; }

npx --prefer-offline -y wrangler@4 r2 bucket cors set "$BUCKET" --file "$DIR/cors.json"
npx --prefer-offline -y wrangler@4 r2 object put "$BUCKET/$NAME" --file "$FILE" --remote \
  --content-type application/octet-stream \
  --cache-control "public, max-age=31536000, immutable"

echo "Uploaded $BUCKET/$NAME. Set VITE_TILES_URL to its public URL (r2.dev in development, tiles.routerank.lk before launch)."
