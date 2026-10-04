from datetime import datetime, timezone

import psycopg

from routerank_import.__main__ import load, write_enrich
from routerank_import.checks import largest_component_share, run_checks
from routerank_import.extract import (english_name, parse_oneway, parse_oneway_bus, places, road_class_of,
                                      road_ref, split_ways)

from .conftest import FIXTURE


def test_road_class_of():
    assert road_class_of("trunk") == ("trunk", False)
    assert road_class_of("primary_link") == ("primary", True)
    assert road_class_of("motorway") is None  # expressways are excluded
    assert road_class_of("residential") is None


def test_oneway_parsing():
    assert parse_oneway({"oneway": "yes"}) == 1
    assert parse_oneway({"oneway": "-1"}) == -1
    assert parse_oneway({}) == 0
    assert parse_oneway({"junction": "roundabout"}) == 1
    assert parse_oneway_bus({"oneway:bus": "no"}) == 0
    assert parse_oneway_bus({}) is None


def test_english_name():
    assert english_name({"name": "කොළඹ", "name:en": "Colombo"}) == "Colombo"
    assert english_name({"name": "Pettah"}) == "Pettah"
    assert english_name({"name": "கொழும்பு"}) is None  # Tamil only: no English name
    assert english_name({"name": "කොළඹ"}) is None  # Sinhala only
    assert english_name({}) is None


def test_fixture_has_place_names():
    found = places(str(FIXTURE))
    assert found
    assert {p.kind for p in found} <= {"city", "town", "suburb", "quarter", "neighbourhood", "village"}
    assert {"quarter", "neighbourhood"} <= {p.kind for p in found}  # Colombo's smaller places
    assert all(p.name for p in found)


def test_road_ref():
    assert road_ref({"ref": " A4 "}) == "A4"
    assert road_ref({"ref": "A2;B84"}) == "A2;B84"
    assert road_ref({}) is None


def test_road_ref():
    assert road_ref({"ref": " A4 "}) == "A4"
    assert road_ref({"ref": "A2;B84"}) == "A2;B84"
    assert road_ref({}) is None


def test_largest_component_share():
    # Two networks: 1-2-3 (30 m) and 7-8 (10 m)
    rows = [(1, 2, 10.0), (2, 3, 20.0), (7, 8, 10.0)]
    assert largest_component_share(rows) == 0.75


def test_ways_split_only_at_shared_nodes():
    pieces, _ = split_ways(str(FIXTURE))
    assert pieces
    # Every piece starts where the previous piece of the same way ended
    by_way: dict[int, list] = {}
    for p in pieces:
        by_way.setdefault(p.osm_way_id, []).append(p)
    for way_pieces in by_way.values():
        way_pieces.sort(key=lambda p: p.seq)
        for a, b in zip(way_pieces, way_pieces[1:]):
            assert a.to_node == b.from_node


def test_colombo_fixture_imports_and_passes_checks(database_url):
    run_id = load(str(FIXTURE), "test://colombo", datetime(2026, 10, 1, tzinfo=timezone.utc),
                  expected_provinces=1)
    with psycopg.connect(database_url) as conn:
        report = run_checks(conn, run_id, expected_provinces=1)
        assert report.failures == [], "\n".join(report.lines)
        provinces = conn.execute("SELECT DISTINCT province FROM road_segment WHERE import_run_id = %s",
                                 (run_id,)).fetchall()
        assert provinces == [("Western",)]
        one_way = conn.execute("SELECT count(*) FROM road_segment WHERE import_run_id = %s AND oneway <> 0",
                               (run_id,)).fetchone()[0]
        assert one_way > 0  # central Colombo has one-way streets
        named = conn.execute("SELECT count(*) FROM place WHERE import_run_id = %s", (run_id,)).fetchone()[0]
        assert named == len(places(str(FIXTURE)))
        named = conn.execute("""SELECT count(*) FILTER (WHERE name IS NOT NULL), count(*) FILTER (WHERE ref IS NOT NULL)
                                FROM road_segment WHERE import_run_id = %s""", (run_id,)).fetchone()
        assert named[0] > 0 and named[1] > 0  # e.g. Galle Road, A2


def test_enrich_adds_names_and_places_to_an_existing_run(database_url, tmp_path):
    run_id = load(str(FIXTURE), "test://colombo-enrich", None, expected_provinces=1)  # the file's own date
    with psycopg.connect(database_url) as conn:
        expected = conn.execute("""SELECT count(*) FILTER (WHERE name IS NOT NULL OR ref IS NOT NULL)
                                   FROM road_segment WHERE import_run_id = %s""", (run_id,)).fetchone()[0]
        places_before = conn.execute("SELECT count(*) FROM place WHERE import_run_id = %s", (run_id,)).fetchone()[0]
        # As production was loaded before Phase 5: no names, and only the four older place kinds
        conn.execute("UPDATE road_segment SET name = NULL, ref = NULL WHERE import_run_id = %s", (run_id,))
        conn.execute("DELETE FROM place WHERE import_run_id = %s AND kind IN ('quarter', 'neighbourhood')", (run_id,))
        ids_before = conn.execute("SELECT array_agg(id ORDER BY id) FROM road_segment WHERE import_run_id = %s",
                                  (run_id,)).fetchone()[0]

    sql = tmp_path / "enrich.sql"
    write_enrich(str(FIXTURE), run_id, str(sql))
    with psycopg.connect(database_url) as conn:
        conn.execute(sql.read_text(encoding="utf-8"))
        named, ids = conn.execute("""SELECT count(*) FILTER (WHERE name IS NOT NULL OR ref IS NOT NULL),
                                            array_agg(id ORDER BY id)
                                     FROM road_segment WHERE import_run_id = %s""", (run_id,)).fetchone()
        assert named == expected
        assert ids == ids_before  # updated in place: saved routes keep their segments
        assert conn.execute("SELECT count(*) FROM place WHERE import_run_id = %s",
                            (run_id,)).fetchone()[0] == places_before

    # Refuses a run from another extract
    with psycopg.connect(database_url) as conn:
        conn.execute("UPDATE import_run SET extract_date = '2025-01-01' WHERE id = %s", (run_id,))
        conn.commit()
        try:
            conn.execute(sql.read_text(encoding="utf-8"))
            raise AssertionError("enrich ran against the wrong extract")
        except psycopg.errors.RaiseException:
            conn.rollback()
