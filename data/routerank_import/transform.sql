-- Turns staged pieces into road_segment rows for one import run.
-- Expects temp tables stg_piece and stg_province (see load.py).
-- Parameters: %(run_id)s, %(cap_m)s (max segment length), %(sliver_m)s.

-- 1. Cut each piece at every point where it touches a province border. A
--    stretch running along a border becomes its own sub-piece (ST_Split
--    refuses those). Positions are fractions 0..1 along the piece. Closed
--    pieces (loops) are not cut, because positions along them are ambiguous.
CREATE TEMP TABLE stg_border AS
SELECT ST_Union(ST_Boundary(geom)) AS geom FROM stg_province;

CREATE TEMP TABLE stg_sub AS
WITH hits AS (
    SELECT p.piece_id, ST_LineLocatePoint(p.geom, (ST_DumpPoints(ST_Intersection(p.geom, b.geom))).geom) AS f
    FROM stg_piece p JOIN stg_border b ON ST_Intersects(p.geom, b.geom)
    WHERE NOT ST_IsClosed(p.geom)
),
cuts AS (
    SELECT piece_id, 0::float8 AS f FROM stg_piece
    UNION SELECT piece_id, 1 FROM stg_piece
    UNION SELECT piece_id, round(f::numeric, 9)::float8 FROM hits
),
spans AS (
    SELECT piece_id, f AS f0, lead(f) OVER (PARTITION BY piece_id ORDER BY f) AS f1 FROM cuts
),
subs AS (
    SELECT s.piece_id, s.f0, s.f1, ST_LineSubstring(p.geom, s.f0, s.f1) AS geom
    FROM spans s JOIN stg_piece p USING (piece_id)
    WHERE s.f1 > s.f0
)
SELECT piece_id, f0, f1,
       ST_Length(geom::geography) AS len_m,
       ST_LineInterpolatePoint(geom, 0.5) AS mid
FROM subs
WHERE ST_Length(geom) > 0;

-- 2. Province of each sub-piece = the province its midpoint is in, or the
--    nearest one when the midpoint falls outside every shape (e.g. coast).
ALTER TABLE stg_sub ADD COLUMN province text;
UPDATE stg_sub s SET province = (
    SELECT pr.name FROM stg_province pr
    ORDER BY ST_Covers(pr.geom, s.mid) DESC, pr.geom <-> s.mid, pr.name
    LIMIT 1);

-- 3. Slivers shorter than sliver_m take their neighbour's province, then
--    consecutive sub-pieces in the same province merge back together.
CREATE TEMP TABLE stg_merged AS
WITH ordered AS (
    SELECT *, lag(province) OVER w AS prev_p, lead(province) OVER w AS next_p,
              count(*) OVER (PARTITION BY piece_id) AS n
    FROM stg_sub WINDOW w AS (PARTITION BY piece_id ORDER BY f0)
),
smoothed AS (
    SELECT piece_id, f0, f1,
           CASE WHEN n > 1 AND len_m < %(sliver_m)s THEN coalesce(prev_p, next_p) ELSE province END AS province
    FROM ordered
),
islands AS (
    SELECT *, sum(CASE WHEN province IS DISTINCT FROM prev THEN 1 ELSE 0 END)
                  OVER (PARTITION BY piece_id ORDER BY f0) AS grp
    FROM (SELECT *, lag(province) OVER (PARTITION BY piece_id ORDER BY f0) AS prev FROM smoothed) x
)
SELECT piece_id, province, min(f0) AS f0, max(f1) AS f1
FROM islands GROUP BY piece_id, province, grp;

-- 4. Cap length: split each merged piece into n equal parts of at most cap_m,
--    then number parts per (way, from_node, to_node) in order along the way.
INSERT INTO road_segment (import_run_id, osm_way_id, from_node, to_node, part, road_class,
                          is_link, oneway, oneway_bus, name, ref, province, geom, length_m)
WITH sized AS (
    SELECT m.*, p.osm_way_id, p.from_node, p.to_node, p.seq, p.road_class, p.is_link,
           p.oneway, p.oneway_bus, p.name, p.ref, p.geom AS piece_geom,
           ST_Length(ST_LineSubstring(p.geom, m.f0, m.f1)::geography) AS len_m
    FROM stg_merged m JOIN stg_piece p USING (piece_id)
),
cut AS (
    SELECT s.*, i, greatest(1, ceil(s.len_m / %(cap_m)s))::int AS n
    FROM sized s,
         LATERAL generate_series(0, greatest(1, ceil(s.len_m / %(cap_m)s))::int - 1) AS i
),
parts AS (
    SELECT c.*,
           ST_LineSubstring(c.piece_geom, c.f0 + (c.f1 - c.f0) * c.i / c.n,
                                          c.f0 + (c.f1 - c.f0) * (c.i + 1) / c.n) AS geom
    FROM cut c
)
SELECT %(run_id)s, osm_way_id, from_node, to_node,
       (row_number() OVER (PARTITION BY osm_way_id, from_node, to_node ORDER BY seq, f0, i) - 1)::smallint,
       road_class, is_link, oneway, oneway_bus, name, ref, province, geom, ST_Length(geom::geography)
FROM parts
WHERE ST_Length(geom) > 0;
