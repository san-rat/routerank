-- Phase 4: place names for naming routes and the search box, plus what saving routes needs.

-- OSM place=* points (city, town, suburb, village) with English names, from the same extract as
-- the road segments. Routes are named from the nearest place to each end; search is a name prefix.
CREATE TABLE place (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    import_run_id bigint NOT NULL REFERENCES import_run (id),
    osm_id        bigint NOT NULL,
    kind          text   NOT NULL CHECK (kind IN ('city', 'town', 'suburb', 'village')),
    name          text   NOT NULL CHECK (name <> ''),
    geom          geometry(Point, 4326) NOT NULL,
    UNIQUE (import_run_id, osm_id)
);
CREATE INDEX place_geom_idx ON place USING gist (geom);
CREATE INDEX place_name_prefix_idx ON place (import_run_id, lower(name) text_pattern_ops);

-- Matching a GraphHopper path looks segments up by OSM way (ADR 0001)
CREATE INDEX road_segment_way_idx ON road_segment (import_run_id, osm_way_id);

-- Auto-name, fixed when the route is saved ("Pettah → Horana")
ALTER TABLE route ADD COLUMN name text NOT NULL DEFAULT '';
ALTER TABLE route ALTER COLUMN name DROP DEFAULT;

-- One route per slot per user, checked at commit, so a reorder or an insert that pushes routes
-- down can move several routes in one transaction (a unique index is checked row by row)
DROP INDEX route_user_slot;
ALTER TABLE route ADD CONSTRAINT route_user_slot
    EXCLUDE USING btree (user_id WITH =, slot WITH =) WHERE (removed_at IS NULL)
    DEFERRABLE INITIALLY DEFERRED;

-- My routes lists a user's routes that are not removed
CREATE INDEX route_user_idx ON route (user_id) WHERE removed_at IS NULL;
