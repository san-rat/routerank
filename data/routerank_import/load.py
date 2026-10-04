"""Write pieces and provinces to PostGIS and build road_segment for a new import run."""

from datetime import datetime
from importlib.resources import files

import psycopg

from .extract import Piece, Place, Province

CAP_M = 1000  # segments are at most ~1 km
SLIVER_M = 25  # border slivers shorter than this join their neighbour


def start_run(conn: psycopg.Connection, extract_date: datetime, source_url: str) -> int:
    return conn.execute(
        "INSERT INTO import_run (extract_date, source_url) VALUES (%s, %s) RETURNING id",
        (extract_date, source_url)).fetchone()[0]


def stage(conn: psycopg.Connection, pieces: list[Piece], provinces: list[Province]) -> None:
    conn.execute("""
        CREATE TEMP TABLE stg_piece (
            piece_id serial PRIMARY KEY, osm_way_id bigint, from_node bigint, to_node bigint,
            seq int, road_class text, is_link boolean, oneway smallint, oneway_bus smallint,
            name text, ref text, wkt text, geom geometry(LineString, 4326))""")
    with conn.cursor().copy(
            "COPY stg_piece (osm_way_id, from_node, to_node, seq, road_class, is_link, oneway, "
            "oneway_bus, name, ref, wkt) FROM STDIN") as copy:
        for p in pieces:
            copy.write_row((p.osm_way_id, p.from_node, p.to_node, p.seq, p.road_class,
                            p.is_link, p.oneway, p.oneway_bus, p.name, p.ref, p.wkt))
    conn.execute("UPDATE stg_piece SET geom = ST_GeomFromText(wkt, 4326)")
    conn.execute("CREATE INDEX ON stg_piece USING gist (geom)")

    conn.execute("""
        CREATE TEMP TABLE stg_province (
            name text PRIMARY KEY, iso_code text, geom geometry(MultiPolygon, 4326))""")
    for pr in provinces:
        conn.execute(
            "INSERT INTO stg_province VALUES (%s, %s, ST_Multi(ST_GeomFromWKB(decode(%s, 'hex'), 4326)))",
            (pr.name, pr.iso_code, pr.wkb_hex))
    conn.execute("CREATE INDEX ON stg_province USING gist (geom)")


def build_segments(conn: psycopg.Connection, run_id: int) -> int:
    sql = files("routerank_import").joinpath("transform.sql").read_text()
    # A client-side cursor allows the multi-statement script with parameters
    with psycopg.ClientCursor(conn) as cur:
        cur.execute(sql, {"run_id": run_id, "cap_m": CAP_M, "sliver_m": SLIVER_M})
    count = conn.execute("SELECT count(*) FROM road_segment WHERE import_run_id = %s",
                         (run_id,)).fetchone()[0]
    conn.execute("UPDATE import_run SET finished_at = now(), segment_count = %s WHERE id = %s",
                 (count, run_id))
    return count


def load_places(conn: psycopg.Connection, run_id: int, places: list[Place]) -> int:
    with conn.cursor().copy("COPY place (import_run_id, osm_id, kind, name, geom) FROM STDIN") as copy:
        for p in places:
            copy.write_row((run_id, p.osm_id, p.kind, p.name, f"SRID=4326;POINT({p.lon:.7f} {p.lat:.7f})"))
    return len(places)
