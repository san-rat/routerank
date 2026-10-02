"""Read a filtered OSM file and produce road pieces and province shapes.

A piece is the stretch of one OSM way between two split nodes. A node splits a
way when it is shared by two or more main-road ways, or is a way's first or
last node. Province and length splitting happen later, in PostGIS.
"""

from collections import Counter
from dataclasses import dataclass

import osmium

MAIN_ROADS = {"trunk", "primary", "secondary"}
LINK_ROADS = {f"{c}_link" for c in MAIN_ROADS}


@dataclass(frozen=True)
class Piece:
    osm_way_id: int
    from_node: int
    to_node: int
    seq: int  # position of this piece along its way
    road_class: str
    is_link: bool
    oneway: int
    oneway_bus: int | None
    wkt: str


@dataclass(frozen=True)
class Province:
    name: str
    iso_code: str
    wkb_hex: str
    bbox: tuple[float, float, float, float]  # min lon, min lat, max lon, max lat


def road_class_of(highway: str | None) -> tuple[str, bool] | None:
    if highway in MAIN_ROADS:
        return highway, False
    if highway in LINK_ROADS:
        return highway.removesuffix("_link"), True
    return None


def parse_oneway(tags) -> int:
    value = tags.get("oneway")
    if value in ("yes", "true", "1"):
        return 1
    if value in ("-1", "reverse"):
        return -1
    if value is None and tags.get("junction") in ("roundabout", "circular"):
        return 1  # implied by OSM convention
    return 0  # "no", untagged, or rare values such as "reversible"


def parse_oneway_bus(tags) -> int | None:
    value = tags.get("oneway:bus")
    if value in ("yes", "true", "1"):
        return 1
    if value in ("-1", "reverse"):
        return -1
    if value in ("no", "false", "0"):
        return 0
    return None


def _main_road_ways(path: str, with_locations: bool):
    fp = osmium.FileProcessor(path, osmium.osm.NODE | osmium.osm.WAY if with_locations else osmium.osm.WAY)
    if with_locations:
        fp = fp.with_locations()
    for obj in fp:
        if obj.is_way() and road_class_of(obj.tags.get("highway")) is not None:
            yield obj


def count_node_use(path: str) -> Counter:
    """How many distinct main-road ways use each node."""
    use = Counter()
    for way in _main_road_ways(path, with_locations=False):
        use.update({n.ref for n in way.nodes})
    return use


def split_ways(path: str) -> tuple[list[Piece], dict[str, int]]:
    use = count_node_use(path)
    pieces: list[Piece] = []
    stats = Counter()
    for way in _main_road_ways(path, with_locations=True):
        road_class, is_link = road_class_of(way.tags.get("highway"))
        oneway, oneway_bus = parse_oneway(way.tags), parse_oneway_bus(way.tags)
        nodes = list(way.nodes)
        if len(nodes) < 2:
            stats["ways_too_short"] += 1
            continue
        try:
            coords = [(n.lon, n.lat) for n in nodes]
        except osmium.InvalidLocationError:
            stats["ways_missing_locations"] += 1
            continue
        start, seq = 0, 0
        for i in range(1, len(nodes)):
            if i == len(nodes) - 1 or use[nodes[i].ref] >= 2:
                line = ", ".join(f"{x:.7f} {y:.7f}" for x, y in coords[start : i + 1])
                pieces.append(Piece(way.id, nodes[start].ref, nodes[i].ref, seq, road_class,
                                    is_link, oneway, oneway_bus, f"LINESTRING({line})"))
                start, seq = i, seq + 1
        stats["ways"] += 1
    stats["pieces"] = len(pieces)
    return pieces, dict(stats)


def provinces(path: str) -> list[Province]:
    """Sri Lankan provinces: admin_level 4 boundaries with an LK- ISO code."""
    wkb = osmium.geom.WKBFactory()
    found = []
    for area in osmium.FileProcessor(path).with_areas():
        if not area.is_area() or area.from_way():
            continue
        tags = area.tags
        iso = tags.get("ISO3166-2", "")
        if tags.get("boundary") == "administrative" and tags.get("admin_level") == "4" and iso.startswith("LK-"):
            name = (tags.get("name:en") or tags.get("name")).removesuffix(" Province")
            points = [(n.lon, n.lat) for ring in area.outer_rings() for n in ring]
            bbox = (min(p[0] for p in points), min(p[1] for p in points),
                    max(p[0] for p in points), max(p[1] for p in points))
            found.append(Province(name, iso, wkb.create_multipolygon(area), bbox))
    return found
