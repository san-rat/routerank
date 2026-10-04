#!/usr/bin/env bash
# Loads one import's roads and places into the production database over TLS. The import itself never runs
# on the VM or against Azure: it runs locally into a fresh database, which is dumped (see data/README.md,
# "Loading production"), and this script restores that dump.
#   data/production/restore.sh roads-YYYY-MM-DD.dump HOST ADMIN_USER
# Needs Docker, and the database firewall open to this machine for the duration (removed afterwards).
# It applies the backend's migrations first (so the place table exists), then restores the data, then
# moves the ID sequences past the restored rows. It asks for the password; nothing is stored.
set -euo pipefail

DUMP="${1:?usage: restore.sh roads-YYYY-MM-DD.dump HOST ADMIN_USER}"
HOST="${2:?usage: restore.sh roads-YYYY-MM-DD.dump HOST ADMIN_USER}"
DB_USER="${3:?usage: restore.sh roads-YYYY-MM-DD.dump HOST ADMIN_USER}"
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$DIR/../.."
FILE="$DIR/../downloads/$DUMP"
[ -f "$FILE" ] || { echo "Not found: $FILE"; exit 1; }
# Docker Desktop on Windows wants Windows paths for bind mounts
win() { if command -v cygpath > /dev/null; then cygpath -w "$1"; else echo "$1"; fi; }

read -rsp "Password for $DB_USER@$HOST: " PGPASSWORD; echo
export PGPASSWORD
export MSYS_NO_PATHCONV=1
SSLMODE="${SSLMODE:-require}" # only for testing against a local database
CONN="host=$HOST port=5432 dbname=routerank user=$DB_USER sslmode=$SSLMODE"

existing=$(docker run --rm -e PGPASSWORD postgres:18 psql "$CONN" -Atc \
  "SELECT count(*) FROM information_schema.tables WHERE table_name = 'import_run'")
if [ "$existing" = "1" ]; then
  runs=$(docker run --rm -e PGPASSWORD postgres:18 psql "$CONN" -Atc "SELECT count(*) FROM import_run")
  if [ "$runs" != "0" ]; then
    echo "Production already has $runs import_run row(s). A re-import needs its routes re-matched; see the Architecture doc."
    exit 1
  fi
fi

echo "Applying migrations..."
FLYWAY_PASSWORD="$PGPASSWORD" docker run --rm -e FLYWAY_PASSWORD -v "$(win "$ROOT/backend/src/main/resources/db/migration"):/migrations:ro" \
  flyway/flyway:12.4.0 -url="jdbc:postgresql://$HOST:5432/routerank?sslmode=$SSLMODE" -user="$DB_USER" \
  -locations=filesystem:/migrations migrate

echo "Restoring $DUMP..."
docker run --rm -e PGPASSWORD -v "$(win "$FILE"):/dump:ro" postgres:18 \
  pg_restore --data-only --no-owner --exit-on-error --single-transaction -d "$CONN" /dump

docker run --rm -e PGPASSWORD postgres:18 psql "$CONN" -v ON_ERROR_STOP=1 -Atc "
  SELECT setval(pg_get_serial_sequence('import_run', 'id'), (SELECT max(id) FROM import_run));
  SELECT setval(pg_get_serial_sequence('road_segment', 'id'), (SELECT max(id) FROM road_segment));
  SELECT setval(pg_get_serial_sequence('place', 'id'), (SELECT max(id) FROM place));
  SELECT 'import_run ' || id || ': extract ' || extract_date || ', ' || segment_count || ' segments, '
         || (SELECT count(*) FROM place WHERE import_run_id = import_run.id) || ' places' FROM import_run;"
echo "Done. Remove the temporary firewall rule now."
