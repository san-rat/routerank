#!/usr/bin/env bash
# Adds road names, refs and the newer place kinds to production's existing import, in place, so saved
# routes keep their segment IDs. The SQL is generated locally from the same filtered file the import used
# (see data/README.md, "Loading production"); nothing is imported on the VM or straight into Azure.
#   data/production/enrich.sh enrich-YYYY-MM-DD.sql HOST ADMIN_USER
# Needs Docker, and the database firewall open to this machine for the duration (removed afterwards).
# It applies the backend's migrations first (so the name and ref columns exist), then runs the SQL in one
# transaction; the SQL refuses to run if the import has another extract date. It asks for the password;
# nothing is stored.
set -euo pipefail

SQL="${1:?usage: enrich.sh enrich-YYYY-MM-DD.sql HOST ADMIN_USER}"
HOST="${2:?usage: enrich.sh enrich-YYYY-MM-DD.sql HOST ADMIN_USER}"
DB_USER="${3:?usage: enrich.sh enrich-YYYY-MM-DD.sql HOST ADMIN_USER}"
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$DIR/../.."
FILE="$DIR/../downloads/$SQL"
[ -f "$FILE" ] || { echo "Not found: $FILE"; exit 1; }
# Docker Desktop on Windows wants Windows paths for bind mounts
win() { if command -v cygpath > /dev/null; then cygpath -w "$1"; else echo "$1"; fi; }

read -rsp "Password for $DB_USER@$HOST: " PGPASSWORD; echo
export PGPASSWORD
export MSYS_NO_PATHCONV=1
SSLMODE="${SSLMODE:-require}" # only for testing against a local database
CONN="host=$HOST port=5432 dbname=routerank user=$DB_USER sslmode=$SSLMODE"

echo "Applying migrations..."
FLYWAY_PASSWORD="$PGPASSWORD" docker run --rm -e FLYWAY_PASSWORD -v "$(win "$ROOT/backend/src/main/resources/db/migration"):/migrations:ro" \
  flyway/flyway:12.4.0 -url="jdbc:postgresql://$HOST:5432/routerank?sslmode=$SSLMODE" -user="$DB_USER" \
  -locations=filesystem:/migrations migrate

echo "Applying $SQL..."
docker run --rm -e PGPASSWORD -v "$(win "$FILE"):/enrich.sql:ro" postgres:18 \
  psql "$CONN" -v ON_ERROR_STOP=1 --single-transaction -Atf /enrich.sql
echo "Done. Remove the temporary firewall rule now."
