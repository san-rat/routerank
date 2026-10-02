"""Phase 1 spike: map a GraphHopper path onto road_segment IDs two ways and compare.

A. By OSM way ID: GraphHopper's osm_way_id path detail gives the way for each stretch
   of the path; keep that way's segments that lie along the stretch.
B. By geometry only: keep segments mostly inside a buffer around the path.

Usage (GraphHopper on :8989, fixture imported, DATABASE_URL set):
  python match.py RUN_ID
"""

import json
import os
import sys
import urllib.parse
import urllib.request

import psycopg

BUFFER_M = 5  # "within a few metres"
MIN_COVER = 0.5  # a segment counts if more than half of it lies along the path

# Start and end points in central Colombo (lat, lon); GraphHopper snaps them to the nearest main road
ROUTES = {
    "Kollupitiya -> Bambalapitiya (southbound)": [(6.9147, 79.8488), (6.8890, 79.8553)],
    "Bambalapitiya -> Kollupitiya (northbound)": [(6.8890, 79.8553), (6.9147, 79.8488)],
    "Pettah -> Borella": [(6.9355, 79.8500), (6.9147, 79.8775)],
}

COVERED = """
    SELECT s.id, s.osm_way_id, s.oneway
    FROM road_segment s
    WHERE s.import_run_id = %(run)s
      AND ST_DWithin(s.geom::geography, ST_GeomFromText(%(line)s, 4326)::geography, %(buf)s)
      AND ST_Length(ST_Intersection(s.geom::geography,
            ST_Buffer(ST_GeomFromText(%(line)s, 4326)::geography, %(buf)s))) > %(cover)s * s.length_m
      {extra}
"""


def route(points):
    query = [("profile", "main_roads"), ("points_encoded", "false"), ("details", "osm_way_id")]
    query += [("point", f"{lat},{lon}") for lat, lon in points]
    with urllib.request.urlopen("http://localhost:8989/route?" + urllib.parse.urlencode(query)) as r:
        return json.load(r)["paths"][0]


def wkt(coords):
    return "LINESTRING(" + ", ".join(f"{x} {y}" for x, y in coords) + ")"


def main(run_id: int) -> None:
    with psycopg.connect(os.environ["DATABASE_URL"]) as conn:
        for name, points in ROUTES.items():
            path = route(points)
            coords = path["points"]["coordinates"]
            params = {"run": run_id, "line": wkt(coords), "buf": BUFFER_M, "cover": MIN_COVER}

            by_geometry = {r[0]: r for r in conn.execute(COVERED.format(extra=""), params)}

            by_way_id = {}
            for start, end, way_id in path["details"]["osm_way_id"]:
                piece = coords[start:end + 1]
                if len(piece) < 2:
                    continue
                rows = conn.execute(COVERED.format(extra="AND s.osm_way_id = %(way)s"),
                                    {**params, "line": wkt(piece), "way": way_id})
                by_way_id.update({r[0]: r for r in rows})

            only_geom = set(by_geometry) - set(by_way_id)
            only_way = set(by_way_id) - set(by_geometry)
            ways_in_path = {w for _, _, w in path["details"]["osm_way_id"]}
            ways_in_db = {r[1] for r in by_way_id.values()}
            print(f"\n{name}: {path['distance'] / 1000:.2f} km, {len(ways_in_path)} OSM ways in path")
            print(f"  segments by way ID: {len(by_way_id)}   by geometry: {len(by_geometry)}   "
                  f"agree: {len(set(by_way_id) & set(by_geometry))}")
            print(f"  only by geometry: {len(only_geom)}   only by way ID: {len(only_way)}")
            print(f"  path ways with no segment in DB: {sorted(ways_in_path - ways_in_db)}")
            if only_geom:
                rows = [by_geometry[i] for i in sorted(only_geom)]
                print("  geometry-only (id, way, oneway):", rows[:8])


if __name__ == "__main__":
    main(int(sys.argv[1]))
