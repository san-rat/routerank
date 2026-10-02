"""Write one-way main roads in an area to GeoJSON for checking against reality.

  python -m routerank_import.spotcheck FILTERED.osm.pbf OUT.geojson [AREA ...]

Open the output on geojson.io or in QGIS; each feature links to its OSM way.
Area boxes are approximate (lon_min, lat_min, lon_max, lat_max).
"""

import json
import sys

import osmium

from .extract import parse_oneway, parse_oneway_bus, road_class_of

AREAS = {
    "colombo": (79.840, 6.880, 79.890, 6.950),  # Colombo 1-7
    "pettah": (79.845, 6.930, 79.865, 6.945),
    "kandy": (80.625, 7.285, 80.645, 7.300),  # Kandy town
}


def inside(lon: float, lat: float, box) -> bool:
    return box[0] <= lon <= box[2] and box[1] <= lat <= box[3]


def main() -> None:
    src, dst, *names = sys.argv[1:]
    boxes = {n: AREAS[n] for n in (names or AREAS)}
    features = []
    for way in osmium.FileProcessor(src, osmium.osm.NODE | osmium.osm.WAY).with_locations():
        if not way.is_way() or road_class_of(way.tags.get("highway")) is None:
            continue
        oneway = parse_oneway(way.tags)
        if oneway == 0:
            continue
        coords = [(n.lon, n.lat) for n in way.nodes]
        areas = [n for n, box in boxes.items() if any(inside(x, y, box) for x, y in coords)]
        if not areas:
            continue
        features.append({
            "type": "Feature",
            "geometry": {"type": "LineString", "coordinates": coords},
            "properties": {
                "name": way.tags.get("name:en") or way.tags.get("name"),
                "highway": way.tags.get("highway"),
                "oneway": oneway,
                "oneway_bus": parse_oneway_bus(way.tags),
                "area": ", ".join(areas),
                "osm": f"https://www.openstreetmap.org/way/{way.id}",
            },
        })
    with open(dst, "w", encoding="utf-8") as f:
        json.dump({"type": "FeatureCollection", "features": features}, f)
    print(f"{len(features)} one-way main-road ways written to {dst}")


if __name__ == "__main__":
    main()
