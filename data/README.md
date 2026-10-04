# Road data import

Turns the OpenStreetMap extract for Sri Lanka into `road_segment` rows (the small pieces of main road that votes score) and `place` rows (the place names that name routes and power search). Rules are in the Architecture doc (Data model → Road segments); the table comes from the backend's Flyway migrations.

## What it does

1. **Filter** (osmium): keep trunk, primary and secondary roads and their `_link` roads, admin boundaries, and `place=city/town/suburb/quarter/neighbourhood/village` points. Expressways (`motorway`) are left out.
2. **Split ways** (pyosmium): cut each way at every node shared by two or more main-road ways. Each piece is keyed by (osm_way_id, from_node, to_node, part).
3. **Provinces**: the nine `admin_level=4` boundaries with an `ISO3166-2` code starting `LK-`. Pieces are cut where they touch a border and take the province of their midpoint.
4. **Length cap**: pieces over 1 km are cut into equal parts. Geometry is EPSG:4326; `length_m` uses `::geography`.
5. **Places**: place points with an English name (`name:en`, or a `name` with no Sinhala or Tamil letters) go to `place`. There is no external geocoder. Each segment also keeps its way's English name and `ref` (road number), so stretches never merge across a change of road.
6. **Checks**: the import fails and rolls back unless every segment has a province, class and length, none is over the cap, the largest connected network holds at least 95% of main-road km, and some places loaded. It prints km per road class and per province, and places per kind.

Each run is recorded in `import_run` with the extract's date.

## Run it (Docker, from `infra/`)

```bash
docker compose up -d db
```
```bash
curl -L https://download.geofabrik.de/asia/sri-lanka-latest.osm.pbf -o ../data/downloads/sri-lanka-latest.osm.pbf
```
```bash
docker compose --profile tools run --rm import filter /work/sri-lanka-latest.osm.pbf /work/main-roads.osm.pbf
```
```bash
docker compose --profile tools run --rm import load /work/main-roads.osm.pbf --source-url https://download.geofabrik.de/asia/sri-lanka-latest.osm.pbf --provinces 9
```

The `import` service applies the Flyway migrations first (the `migrate` service). On Git Bash for Windows, prefix the `docker compose` commands with `MSYS_NO_PATHCONV=1` so `/work/...` paths are not rewritten.

## Routing graph

The API routes with an embedded GraphHopper graph built from the **same filtered file** as the segments, never on the VM. GraphHopper keeps the file's extract date, and the API refuses to start unless it matches the newest `import_run`.

```bash
cd ../backend && ./gradlew buildGraph -Posm=../data/downloads/main-roads.osm.pbf -Pout=../data/downloads/graph-2026-10-01
```
```bash
data/graph/upload.sh graph-2026-10-01
```

The graph is about 8 MB (2.8 MB packed). `upload.sh` puts it on R2 under `graphs/`; then set the name, URL and SHA-256 in `infra/azure/graph.env`, and the next API deploy downloads it to the VM.

For local development, run the API with `ROUTERANK_ROUTING_GRAPH_LOCATION` pointing at the graph folder, against a database holding the same import.

## Loading production

The import never runs on the VM or straight into Azure. Import into a fresh local database, dump the road and place tables, and restore the dump over TLS:

```bash
docker compose exec db psql -U routerank -c "CREATE DATABASE routerank_export"
```
```bash
docker compose run --rm --no-deps migrate -url=jdbc:postgresql://db:5432/routerank_export -user=routerank -password=routerank -locations=filesystem:/migrations migrate
```
```bash
docker compose --profile tools run --rm --no-deps -e DATABASE_URL=postgresql://routerank:routerank@db:5432/routerank_export import load /work/main-roads.osm.pbf --source-url https://download.geofabrik.de/asia/sri-lanka-latest.osm.pbf --provinces 9
```
```bash
docker compose exec db pg_dump -U routerank -d routerank_export -Fc --data-only -t import_run -t road_segment -t place -f /tmp/roads.dump
```

Copy it out (`docker compose cp db:/tmp/roads.dump ../data/downloads/roads-2026-10-01.dump`), open the Azure database firewall to your IP with a temporary rule, run `data/production/restore.sh roads-2026-10-01.dump <server>.postgres.database.azure.com <admin user>` (it asks for the password, applies the migrations, restores, and fixes the ID sequences), then delete the firewall rule. The script refuses to run when production already has an import: a re-import must also re-match stored routes onto the new segments (Phase 8).

### Adding names to an existing import

Production's import predates road names, refs and the `quarter` and `neighbourhood` places (Phase 5). Rather than re-import (which would change the segment IDs saved routes point to), `enrich` writes SQL that updates the same run in place from the same filtered file. Re-filter first so the file has the new place kinds (same extract, same date), then generate the SQL:

```bash
docker compose --profile tools build import
```
```bash
docker compose --profile tools run --rm --no-deps import filter /work/sri-lanka-latest.osm.pbf /work/main-roads.osm.pbf
```
```bash
docker compose --profile tools run --rm --no-deps import enrich /work/main-roads.osm.pbf --run-id 1 /work/enrich-2026-10-01.sql
```

Apply it locally with `docker compose exec -T db psql -U routerank -d routerank -v ON_ERROR_STOP=1 --single-transaction -f - < ../data/downloads/enrich-2026-10-01.sql`, and to production like the restore: open the firewall to your IP with a temporary rule, run `data/production/enrich.sh enrich-2026-10-01.sql <server>.postgres.database.azure.com <admin user>` (it asks for the password, applies the migrations, then runs the SQL in one transaction, which refuses to run against an import with another extract date), then delete the rule. The routing graph doesn't change.

## Spot checks

Export one-way main roads in Colombo 1–7, Pettah and Kandy town to GeoJSON and compare them with reality (geojson.io or QGIS; each feature links to its OSM way):

```bash
docker compose --profile tools run --rm --entrypoint python import -m routerank_import.spotcheck /work/main-roads.osm.pbf /work/spotcheck-oneway.geojson
```

Fix wrong tags upstream in OpenStreetMap, then re-import.

## Tests

`fixtures/colombo.osm.pbf` is central Colombo plus the Western Province boundary (216 KB), with the extract date in its header. The tests start PostGIS with Testcontainers (Docker needed), apply the migrations, import the fixture and run the checks.

The backend's routing tests use the same fixture: its graph is built on test startup, and its roads and places load from `backend/src/test/resources/db/testdata/V1000__colombo_roads.sql`. Regenerate that file after changing the import or the fixture with `.venv/Scripts/python -m tests.make_backend_fixture`.

```bash
python -m venv .venv
```
```bash
.venv/Scripts/python -m pip install -r requirements-dev.txt
```
```bash
.venv/Scripts/python -m pytest
```

On macOS or Linux, use `.venv/bin/python` instead.

## Base map (Phase 2)

The map the website draws is one PMTiles file cut from Protomaps' daily build of OpenStreetMap, named by its build date and never overwritten (it is cached as immutable).

```bash
data/basemap/build.sh 20261002
```
```bash
data/basemap/upload.sh sri-lanka-2026-10-02.pmtiles routerank-tiles
```

`build.sh` runs `pmtiles extract` (Docker, `protomaps/go-pmtiles`) with a Sri Lanka bounding box and writes `downloads/sri-lanka-YYYY-MM-DD.pmtiles` (about 175 MB, zoom 0–15). `upload.sh` needs Wrangler logged in to Cloudflare; it sets the bucket's CORS rules from `basemap/cors.json` and uploads the file with a one-year immutable cache header. Then point `VITE_TILES_URL` at the new file.

`python -m routerank_import provinces downloads/main-roads.osm.pbf ../frontend/src/map/provinces.json` refreshes the province list and bounding boxes the map's picker uses.

## Matching spike

`spikes/graphhopper/` shows how a GraphHopper path maps to segment IDs. Result: [docs/adr/0001-route-to-segment-matching.md](../docs/adr/0001-route-to-segment-matching.md).

## Licence

Data © OpenStreetMap contributors, ODbL. See [ATTRIBUTION.md](ATTRIBUTION.md).
