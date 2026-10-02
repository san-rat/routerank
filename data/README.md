# Road data import

Turns the OpenStreetMap extract for Sri Lanka into `road_segment` rows: the small pieces of main road that votes score. Rules are in the Architecture doc (Data model → Road segments); the table comes from the backend's Flyway migrations.

## What it does

1. **Filter** (osmium): keep trunk, primary and secondary roads and their `_link` roads, plus admin boundaries. Expressways (`motorway`) are left out.
2. **Split ways** (pyosmium): cut each way at every node shared by two or more main-road ways. Each piece is keyed by (osm_way_id, from_node, to_node, part).
3. **Provinces**: the nine `admin_level=4` boundaries with an `ISO3166-2` code starting `LK-`. Pieces are cut where they touch a border and take the province of their midpoint.
4. **Length cap**: pieces over 1 km are cut into equal parts. Geometry is EPSG:4326; `length_m` uses `::geography`.
5. **Checks**: the import fails and rolls back unless every segment has a province, class and length, none is over the cap, and the largest connected network holds at least 95% of main-road km. It prints km per road class and per province.

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

## Spot checks

Export one-way main roads in Colombo 1–7, Pettah and Kandy town to GeoJSON and compare them with reality (geojson.io or QGIS; each feature links to its OSM way):

```bash
docker compose --profile tools run --rm --entrypoint python import -m routerank_import.spotcheck /work/main-roads.osm.pbf /work/spotcheck-oneway.geojson
```

Fix wrong tags upstream in OpenStreetMap, then re-import.

## Tests

`fixtures/colombo.osm.pbf` is central Colombo plus the Western Province boundary (216 KB). The tests start PostGIS with Testcontainers (Docker needed), apply the migrations, import the fixture and run the checks.

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
