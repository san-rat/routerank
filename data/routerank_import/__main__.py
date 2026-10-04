"""RouteRank road import.

  python -m routerank_import filter IN.osm.pbf OUT.osm.pbf
  python -m routerank_import load FILTERED.osm.pbf --source-url URL [--extract-date ISO] [--provinces 9]
  python -m routerank_import provinces FILTERED.osm.pbf OUT.json

The database comes from DATABASE_URL (libpq form, e.g. postgresql://user:pass@host/db).
"""

import argparse
import json
import os
import subprocess
import sys
from datetime import datetime, timezone

import osmium
import psycopg

from .checks import run_checks
from .extract import places, provinces, split_ways
from .load import build_segments, load_places, stage, start_run

ROAD_FILTER = "w/highway=trunk,trunk_link,primary,primary_link,secondary,secondary_link"
BOUNDARY_FILTER = "r/boundary=administrative"
PLACE_FILTER = "n/place=city,town,suburb,village"


def filter_extract(src: str, dst: str) -> None:
    subprocess.run(["osmium", "tags-filter", src, ROAD_FILTER, BOUNDARY_FILTER, PLACE_FILTER,
                    "-o", dst, "--overwrite"], check=True)


def extract_date_of(path: str) -> datetime | None:
    stamp = osmium.io.Reader(path).header().get("osmosis_replication_timestamp")
    return datetime.fromisoformat(stamp.replace("Z", "+00:00")) if stamp else None


def load(path: str, source_url: str, extract_date: datetime | None, expected_provinces: int | None) -> int:
    extract_date = extract_date or extract_date_of(path)
    if extract_date is None:
        sys.exit("No extract date in the file header; pass --extract-date")

    pieces, stats = split_ways(path)
    shapes = provinces(path)
    print(f"ways {stats.get('ways', 0):,} -> pieces {stats['pieces']:,}; "
          f"provinces {len(shapes)}; skipped {({k: v for k, v in stats.items() if k.startswith('ways_')})}")
    if not shapes:
        sys.exit("No Sri Lankan province boundaries (admin_level 4, ISO3166-2 LK-*) in the file")

    with psycopg.connect(os.environ["DATABASE_URL"]) as conn:
        run_id = start_run(conn, extract_date, source_url)
        stage(conn, pieces, shapes)
        count = build_segments(conn, run_id)
        named = load_places(conn, run_id, places(path))
        print(f"import_run {run_id}: {count:,} segments, {named:,} places (extract {extract_date.isoformat()})")
        report = run_checks(conn, run_id, expected_provinces)
        print("\n".join(report.lines))
        if report.failures:
            conn.rollback()
            sys.exit(f"\n{len(report.failures)} check(s) failed; import rolled back")
    print("\nAll checks passed.")
    return run_id


def write_provinces(path: str, out: str) -> None:
    """Province list for the map's picker: name, URL slug, ISO code and bounding box."""
    shapes = sorted(provinces(path), key=lambda p: p.name)
    rows = [{"name": p.name, "slug": p.name.lower().replace(" ", "-"), "iso": p.iso_code,
             "bbox": [round(v, 4) for v in p.bbox]} for p in shapes]
    with open(out, "w", encoding="utf-8") as f:
        json.dump(rows, f, indent=2)
        f.write("\n")
    print(f"{len(rows)} provinces written to {out}")


def main() -> None:
    parser = argparse.ArgumentParser(prog="routerank_import")
    sub = parser.add_subparsers(dest="command", required=True)
    f = sub.add_parser("filter", help="keep main roads, admin boundaries and place points")
    f.add_argument("src")
    f.add_argument("dst")
    ld = sub.add_parser("load", help="split roads into segments, load them and run the checks")
    ld.add_argument("pbf")
    ld.add_argument("--source-url", required=True)
    ld.add_argument("--extract-date", type=datetime.fromisoformat)
    ld.add_argument("--provinces", type=int, help="expected number of provinces (9 for Sri Lanka)")
    pr = sub.add_parser("provinces", help="write province names, slugs and bounding boxes as JSON")
    pr.add_argument("pbf")
    pr.add_argument("out")
    args = parser.parse_args()

    if args.command == "filter":
        filter_extract(args.src, args.dst)
    elif args.command == "provinces":
        write_provinces(args.pbf, args.out)
    else:
        date = args.extract_date.replace(tzinfo=args.extract_date.tzinfo or timezone.utc) if args.extract_date else None
        load(args.pbf, args.source_url, date, args.provinces)


if __name__ == "__main__":
    main()
