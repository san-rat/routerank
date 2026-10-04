-- Phase 6: bus routes drawn by admins, extensions, anti-fraud holds and the admin role.

-- Bus routes are drawn in the admin form with the same main-road routing as user routes, so they keep
-- their points like routes do. No length limit: the 40 km cap is only for what users vote for. The table
-- has been empty since Phase 3, so the new columns can be NOT NULL.
ALTER TABLE bus_route
    ADD COLUMN start_name  text NOT NULL CHECK (start_name <> ''),
    ADD COLUMN end_name    text NOT NULL CHECK (end_name <> ''),
    ADD COLUMN start_point geometry(Point, 4326) NOT NULL,
    ADD COLUMN end_point   geometry(Point, 4326) NOT NULL,
    -- Towns it passes, in order, for its sheet (W05)
    ADD COLUMN towns       text[] NOT NULL DEFAULT '{}',
    ADD COLUMN created_at  timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN updated_at  timestamptz NOT NULL DEFAULT now(),
    -- Never deleted, so extensions keep pointing at it; a retired route can't be extended any more
    ADD COLUMN retired_at  timestamptz;
ALTER TABLE bus_route ADD CONSTRAINT bus_route_number_check CHECK (number <> '');
CREATE UNIQUE INDEX bus_route_number_active ON bus_route (number) WHERE retired_at IS NULL;

CREATE TABLE bus_route_waypoint (
    bus_route_id bigint   NOT NULL REFERENCES bus_route (id),
    seq          smallint NOT NULL CHECK (seq >= 0),
    point        geometry(Point, 4326) NOT NULL,
    PRIMARY KEY (bus_route_id, seq)
);

-- The segments either direction of the bus runs on; an extension's segments on these don't score
CREATE TABLE bus_route_segment (
    bus_route_id bigint NOT NULL REFERENCES bus_route (id),
    segment_id   bigint NOT NULL REFERENCES road_segment (id),
    PRIMARY KEY (bus_route_id, segment_id)
);

CREATE INDEX route_bus_route_idx ON route (bus_route_id) WHERE bus_route_id IS NOT NULL;

-- The admin role is set by hand in the database (never through the API):
--   UPDATE app_user SET role = 'admin' WHERE email = '...';
ALTER TABLE app_user ADD COLUMN role text NOT NULL DEFAULT 'voter' CHECK (role IN ('voter', 'admin'));

-- A held account's routes, and any it saves later, stay out of the rankings until an admin releases it.
-- Holds come only from fraud triggers; they are never turned into bans automatically.
ALTER TABLE app_user ADD COLUMN held_at timestamptz;
COMMENT ON COLUMN app_user.trust_score IS
    'Sum of the weights of the account''s open fraud flags; only sorts the review queue, never decides holds';

-- Every role change is audited, including the ones made by hand in the database
CREATE FUNCTION audit_role_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO audit_log (actor_id, action, target, before, after, reason)
    VALUES (NULL, 'account.role', 'account:' || NEW.id, jsonb_build_object('role', OLD.role),
            jsonb_build_object('role', NEW.role), 'Changed in the database by ' || session_user);
    RETURN NEW;
END $$;

CREATE TRIGGER audit_role_change
    AFTER UPDATE OF role ON app_user
    FOR EACH ROW WHEN (OLD.role IS DISTINCT FROM NEW.role) EXECUTE FUNCTION audit_role_change();

-- Fraud flags: one per account and trigger (a released account isn't flagged again for the same trigger).
-- cluster_key groups the review queue: accounts on one device, or one burst of new accounts.
-- The table has been empty since Phase 3.
ALTER TABLE fraud_flag DROP COLUMN route_id;
ALTER TABLE fraud_flag
    ADD COLUMN cluster_key text NOT NULL,
    ADD COLUMN weight      real NOT NULL DEFAULT 1 CHECK (weight >= 0),
    ADD COLUMN reviewed_at timestamptz;
ALTER TABLE fraud_flag ADD CONSTRAINT fraud_flag_reason_check
    CHECK (reason IN ('shared_device', 'burst', 'honeypot'));
CREATE UNIQUE INDEX fraud_flag_user_reason ON fraud_flag (user_id, reason);
CREATE INDEX fraud_flag_cluster ON fraud_flag (cluster_key);

-- Device signals are deleted 90 days after they were last seen
CREATE INDEX device_signal_last_seen ON device_signal (last_seen);
