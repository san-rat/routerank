-- A bus can come back on a different road: its way back gets waypoints of its own (Spec, "Way there and way
-- back"). Existing waypoints are all for the way there; with no way-back waypoints the way back stays the fastest.
ALTER TABLE bus_route_waypoint
    ADD COLUMN leg text NOT NULL DEFAULT 'out' CHECK (leg IN ('out', 'back'));
ALTER TABLE bus_route_waypoint DROP CONSTRAINT bus_route_waypoint_pkey;
ALTER TABLE bus_route_waypoint ADD PRIMARY KEY (bus_route_id, leg, seq);

-- The roads the way back uses where it leaves the way there ("Duplication Road"), for the bus's sheet. Bus routes
-- drawn before this have none until they are redrawn; their dashes are worked out from the lines when published.
ALTER TABLE bus_route ADD COLUMN back_via text[] NOT NULL DEFAULT '{}';
