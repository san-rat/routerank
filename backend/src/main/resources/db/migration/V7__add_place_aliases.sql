-- Phase 5: well-known areas missing from OpenStreetMap (data/places-extra.csv) join the place table with a
-- negative osm_id, and any place can have other names that search also finds ("Pitakotuwa" finds Pettah).
ALTER TABLE place ADD COLUMN aliases text[] NOT NULL DEFAULT '{}';
COMMENT ON COLUMN place.osm_id IS 'OSM node ID; negative for a row from data/places-extra.csv';
COMMENT ON COLUMN place.aliases IS 'Other names search also matches; results show the main name';
