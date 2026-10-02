# 0001 — Matching a GraphHopper path to road segments

- **Status:** accepted for Phase 4, from the Phase 1 spike
- **Date:** 2026-10-02

## Decision

Map a route onto `road_segment` rows **by OSM way ID**: ask GraphHopper for the `osm_way_id` path detail, and for each stretch of the path keep the segments of that way that lie along it (within 5 m, covering more than half of the segment). Don't match by geometry alone.

## What the spike did

`data/spikes/graphhopper/` runs GraphHopper 11.1 on the Colombo fixture with a main-roads-only profile (`car.json` plus `main_roads.json`) and `graph.encoded_values` including `osm_way_id`, `road_class` and `road_class_link`. `match.py` routes three trips and maps each path to segments two ways:

- **A. By way ID:** the `osm_way_id` detail, then that way's segments within 5 m of the stretch.
- **B. By geometry:** any segment within 5 m of the path with more than 50% of its length inside the buffer.

| Route | Path | A (way ID) | B (geometry) | In both | Only B |
| --- | --- | --- | --- | --- | --- |
| Kollupitiya → Bambalapitiya (southbound) | 4.48 km | 28 | 32 | 28 | 4 |
| Bambalapitiya → Kollupitiya (northbound) | 3.39 km | 24 | 26 | 24 | 2 |
| Pettah → Borella | 4.81 km | 47 | 52 | 47 | 5 |

## Findings

1. **GraphHopper returns OSM way IDs** when `osm_way_id` is in `graph.encoded_values` and the request has `details=osm_way_id`. Every way in the three paths exists in `road_segment`.
2. **Everything A finds, B finds too**, so the two agree on the road actually driven.
3. **B adds false positives:** 11 extra segments, all short (8–72 m): 5 are junction links and 6 are one-way trunk or secondary pieces lying within 5 m of the path, most likely the opposite carriageway or a crossing at a junction (not inspected on a map). Matching by way ID avoids them because it never looks at other ways.
4. **Partial segments at the ends:** on Pettah → Borella, two ways in the path got no segment, because the path covered less than half of them (the route starts or ends partway along). This is the "more than 50%" rule from the Phase 4 open question working as written; if it is changed, these change too.
5. **One-way:** the southbound and northbound Galle Road trips have different lengths (4.48 km vs 3.39 km), which is consistent with the car profile following the `oneway` tags; I did not trace the two paths street by street.

## Notes for Phase 4

- Geometry matching is still needed **after a re-import**: stored routes have no GraphHopper path, so they are re-matched from their geometry onto new segments, as the Architecture doc says. Expect the same false positives there; filter them by checking that the segment runs in the direction of travel.
- `oneway:bus` is tagged on only 1 segment in all of Sri Lanka (import of 2026-10-01), so the car profile is the right start. GraphHopper ships a `bus.json` model that uses `bus_access`; whether that honours `oneway:bus` was not tested.
