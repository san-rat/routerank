"""Pass/fail checks on one import run. Every check prints its result; any failure fails the run."""

from dataclasses import dataclass, field

import psycopg

from .load import CAP_M

CAP_TOLERANCE = 1.01  # equal parts are cut on the projected line; allow 1% for that
MIN_CONNECTED_SHARE = 0.95  # "almost all" main-road km in the largest connected network


@dataclass
class Report:
    failures: list[str] = field(default_factory=list)
    lines: list[str] = field(default_factory=list)

    def check(self, ok: bool, message: str) -> None:
        self.lines.append(f"[{'PASS' if ok else 'FAIL'}] {message}")
        if not ok:
            self.failures.append(message)

    def info(self, message: str) -> None:
        self.lines.append(message)


def largest_component_share(rows: list[tuple[int, int, float]]) -> float:
    """Share of length in the largest network, joining pieces that share a node (direction ignored)."""
    parent: dict[int, int] = {}

    def find(x: int) -> int:
        parent.setdefault(x, x)
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for a, b, _ in rows:
        parent[find(a)] = find(b)
    totals: dict[int, float] = {}
    for a, _, length in rows:
        root = find(a)
        totals[root] = totals.get(root, 0) + length
    total = sum(totals.values())
    return max(totals.values()) / total if total else 0.0


def run_checks(conn: psycopg.Connection, run_id: int, expected_provinces: int | None) -> Report:
    r = Report()
    q = lambda sql: conn.execute(sql, {"run": run_id}).fetchall()

    count = q("SELECT count(*) FROM road_segment WHERE import_run_id = %(run)s")[0][0]
    r.check(count > 0, f"segments imported: {count:,}")

    missing = q("""SELECT count(*) FROM road_segment WHERE import_run_id = %(run)s
                   AND (province IS NULL OR province = '' OR road_class IS NULL
                        OR length_m IS NULL OR length_m <= 0)""")[0][0]
    r.check(missing == 0, f"every segment has a province, a road class and a length ({missing} missing)")

    longest = q("SELECT coalesce(max(length_m), 0) FROM road_segment WHERE import_run_id = %(run)s")[0][0]
    r.check(longest <= CAP_M * CAP_TOLERANCE,
            f"no segment over the {CAP_M} m cap (longest {longest:.0f} m)")

    provinces = q("SELECT count(DISTINCT province) FROM road_segment WHERE import_run_id = %(run)s")[0][0]
    if expected_provinces is not None:
        r.check(provinces == expected_provinces,
                f"segments in {provinces} provinces (expected {expected_provinces})")

    r.info("\nkm by road class:")
    for road_class, is_link, km, n in q("""
            SELECT road_class, is_link, sum(length_m) / 1000, count(*) FROM road_segment
            WHERE import_run_id = %(run)s GROUP BY 1, 2 ORDER BY 1, 2"""):
        r.info(f"  {road_class + ('_link' if is_link else ''):<16} {km:>9,.1f} km  {n:>7,} segments")

    r.info("\nkm by province:")
    for province, km, n in q("""
            SELECT province, sum(length_m) / 1000, count(*) FROM road_segment
            WHERE import_run_id = %(run)s GROUP BY 1 ORDER BY 2 DESC"""):
        r.info(f"  {province:<16} {km:>9,.1f} km  {n:>7,} segments")

    named_km, total_km = q("""SELECT sum(length_m) FILTER (WHERE name IS NOT NULL OR ref IS NOT NULL) / 1000,
                                     sum(length_m) / 1000 FROM road_segment WHERE import_run_id = %(run)s""")[0]
    r.info(f"\nkm with a road name or number: {named_km or 0:,.1f} of {total_km or 0:,.1f}")

    r.info("\nplaces by kind:")
    kinds = q("SELECT kind, count(*) FROM place WHERE import_run_id = %(run)s GROUP BY 1 ORDER BY 2 DESC")
    for kind, n in kinds:
        r.info(f"  {kind:<16} {n:>7,}")
    r.check(sum(n for _, n in kinds) > 0, "place names loaded (they name routes and power search)")

    rows = q("""SELECT from_node, to_node, sum(length_m) FROM road_segment
                WHERE import_run_id = %(run)s GROUP BY 1, 2""")
    share = largest_component_share(rows)
    r.info("")
    r.check(share >= MIN_CONNECTED_SHARE,
            f"largest connected network holds {share:.1%} of main-road km (need {MIN_CONNECTED_SHARE:.0%})")
    return r
