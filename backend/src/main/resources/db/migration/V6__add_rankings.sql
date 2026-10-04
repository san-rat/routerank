-- Phase 5: rankings. The scoring job rebuilds segment_score and stretch every 30 minutes and publishes
-- the results to R2; the tables below are what it needs to keep between runs.

-- A stretch never runs across a change of road name or number (ref, e.g. "A4")
ALTER TABLE road_segment ADD COLUMN name text, ADD COLUMN ref text;

-- Smaller places too, for search and naming: Colombo's quarters and neighbourhoods
ALTER TABLE place DROP CONSTRAINT place_kind_check;
ALTER TABLE place ADD CONSTRAINT place_kind_check
    CHECK (kind IN ('city', 'town', 'suburb', 'quarter', 'neighbourhood', 'village'));

-- Rebuilt by every scoring run. Ranks are empty for stretches not on the leaderboards (under 1 km, fewer
-- than 3 people, or only link roads); the heatmap still shows them.
DROP TABLE stretch;
CREATE TABLE stretch (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slug          text     NOT NULL UNIQUE,
    name          text     NOT NULL,
    province      text     NOT NULL,
    segment_ids   bigint[] NOT NULL,
    length_m      double precision NOT NULL CHECK (length_m > 0),
    points        integer  NOT NULL CHECK (points >= 0),
    people        integer  NOT NULL CHECK (people >= 0),
    rank_overall  integer,
    rank_province integer
);
-- My routes looks up the stretches a route's segments are in
CREATE INDEX stretch_segments_idx ON stretch USING gin (segment_ids);

-- A stretch link (/s/<slug>) opens whichever stretch contains its anchor segment now, so links survive
-- stretches being re-merged as votes change
CREATE TABLE stretch_link (
    slug              text PRIMARY KEY,
    anchor_segment_id bigint NOT NULL REFERENCES road_segment (id) ON DELETE CASCADE,
    created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX stretch_link_anchor_idx ON stretch_link (anchor_segment_id);

-- Files on R2. Names carry a content hash, so a file is uploaded once and then only referenced; files no
-- manifest has referenced for 2 days are deleted
CREATE TABLE published_file (
    key                text PRIMARY KEY,
    first_published_at timestamptz NOT NULL,
    last_referenced_at timestamptz NOT NULL
);

-- R2 writes (Class A operations) per calendar month (UTC); uploads stop at the budget, inside the free tier
CREATE TABLE publish_usage (
    month  date PRIMARY KEY,
    writes integer NOT NULL CHECK (writes >= 0)
);
