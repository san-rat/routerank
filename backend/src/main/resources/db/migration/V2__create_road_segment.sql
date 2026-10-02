-- One row per OpenStreetMap import. Segments point to the run that made them,
-- so a re-import can be compared with the previous one before switching over.
CREATE TABLE import_run (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    extract_date  timestamptz NOT NULL,
    source_url    text        NOT NULL,
    started_at    timestamptz NOT NULL DEFAULT now(),
    finished_at   timestamptz,
    segment_count integer
);

-- Main roads split at nodes shared by two or more main-road ways, at province
-- borders, and into equal parts of at most ~1 km. Votes score these segments.
CREATE TABLE road_segment (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    import_run_id bigint   NOT NULL REFERENCES import_run (id),
    osm_way_id    bigint   NOT NULL,
    from_node     bigint   NOT NULL,
    to_node       bigint   NOT NULL,
    part          smallint NOT NULL CHECK (part >= 0),
    road_class    text     NOT NULL CHECK (road_class IN ('trunk', 'primary', 'secondary')),
    is_link       boolean  NOT NULL,
    -- Direction relative to the OSM way: 1 = forward only, -1 = backward only, 0 = both
    oneway        smallint NOT NULL CHECK (oneway IN (-1, 0, 1)),
    -- oneway:bus when tagged (0 = buses may use both directions), NULL when untagged
    oneway_bus    smallint CHECK (oneway_bus IN (-1, 0, 1)),
    province      text     NOT NULL,
    geom          geometry(LineString, 4326) NOT NULL,
    length_m      double precision NOT NULL CHECK (length_m > 0),
    UNIQUE (import_run_id, osm_way_id, from_node, to_node, part)
);

CREATE INDEX road_segment_geom_idx ON road_segment USING gist (geom);
CREATE INDEX road_segment_province_idx ON road_segment (province);
