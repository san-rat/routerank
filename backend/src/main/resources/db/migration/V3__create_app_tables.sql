-- Accounts, routes (votes), bus routes, scoring output, admin and anti-fraud tables.
-- See the Architecture doc's data model. Votes are stored once as routes; scores are derived.

CREATE TABLE app_user (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    google_sub  text        NOT NULL UNIQUE,
    email       text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    -- Votes count only once the account is 24 hours old; set by the app on sign-up
    live_at     timestamptz NOT NULL,
    trust_score real        NOT NULL DEFAULT 0,
    banned_at   timestamptz,
    CHECK (live_at >= created_at)
);

CREATE TABLE bus_route (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    number        text NOT NULL,
    name          text NOT NULL,
    geom_out      geometry(LineString, 4326) NOT NULL,
    geom_back     geometry(LineString, 4326) NOT NULL,
    length_out_m  double precision NOT NULL CHECK (length_out_m > 0),
    length_back_m double precision NOT NULL CHECK (length_back_m > 0)
);

CREATE TABLE route (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       bigint   NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    slot          smallint NOT NULL CHECK (slot BETWEEN 1 AND 3),
    kind          text     NOT NULL CHECK (kind IN ('new', 'extension')),
    bus_route_id  bigint REFERENCES bus_route (id),
    start_point   geometry(Point, 4326)      NOT NULL,
    end_point     geometry(Point, 4326)      NOT NULL,
    geom_out      geometry(LineString, 4326) NOT NULL,
    geom_back     geometry(LineString, 4326) NOT NULL,
    length_out_m  double precision NOT NULL CHECK (length_out_m > 0),
    length_back_m double precision NOT NULL CHECK (length_back_m > 0),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    held_at       timestamptz,
    removed_at    timestamptz,
    CHECK ((kind = 'extension') = (bus_route_id IS NOT NULL))
);
-- One route per slot per user; removed routes stay for review and don't take the slot
CREATE UNIQUE INDEX route_user_slot ON route (user_id, slot) WHERE removed_at IS NULL;

CREATE TABLE route_waypoint (
    route_id bigint   NOT NULL REFERENCES route (id) ON DELETE CASCADE,
    seq      smallint NOT NULL CHECK (seq >= 0),
    point    geometry(Point, 4326) NOT NULL,
    PRIMARY KEY (route_id, seq)
);

-- One row per segment either direction uses; scores = false for the bus part of an extension
CREATE TABLE route_segment (
    route_id   bigint  NOT NULL REFERENCES route (id) ON DELETE CASCADE,
    segment_id bigint  NOT NULL REFERENCES road_segment (id),
    scores     boolean NOT NULL,
    PRIMARY KEY (route_id, segment_id)
);
CREATE INDEX route_segment_segment ON route_segment (segment_id);

-- Enforces the 24-hour slot cooldown
CREATE TABLE slot_change (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    bigint   NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    slot       smallint NOT NULL CHECK (slot BETWEEN 1 AND 3),
    changed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX slot_change_user_slot ON slot_change (user_id, slot, changed_at DESC);

-- Written by the scoring job
CREATE TABLE segment_score (
    segment_id  bigint  PRIMARY KEY REFERENCES road_segment (id) ON DELETE CASCADE,
    points      integer NOT NULL CHECK (points >= 0),
    people      integer NOT NULL CHECK (people >= 0),
    computed_at timestamptz NOT NULL
);

CREATE TABLE stretch (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          text     NOT NULL,
    province      text     NOT NULL,
    segment_ids   bigint[] NOT NULL,
    points        integer  NOT NULL CHECK (points >= 0),
    people        integer  NOT NULL CHECK (people >= 0),
    rank_overall  integer,
    rank_province integer
);

-- Append-only: no foreign key on actor_id so deleting an account never rewrites history
CREATE TABLE audit_log (
    id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id bigint,
    action   text  NOT NULL,
    target   text  NOT NULL,
    before   jsonb,
    after    jsonb,
    reason   text  NOT NULL,
    at       timestamptz NOT NULL DEFAULT now()
);

CREATE FUNCTION audit_log_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only';
END $$;

CREATE TRIGGER audit_log_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_log_append_only();

-- Written by the nightly fraud scan; drives the admin review queue
CREATE TABLE fraud_flag (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    route_id    bigint REFERENCES route (id) ON DELETE CASCADE,
    user_id     bigint NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    reason      text   NOT NULL,
    signals     jsonb  NOT NULL DEFAULT '{}',
    status      text   NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'released', 'removed')),
    reviewed_by bigint REFERENCES app_user (id) ON DELETE SET NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX fraud_flag_open ON fraud_flag (created_at) WHERE status = 'open';

-- Keyed hash (HMAC) only, never the raw fingerprint
CREATE TABLE device_signal (
    user_id     bigint NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    device_hash bytea  NOT NULL,
    first_seen  timestamptz NOT NULL DEFAULT now(),
    last_seen   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, device_hash)
);
CREATE INDEX device_signal_hash ON device_signal (device_hash);
