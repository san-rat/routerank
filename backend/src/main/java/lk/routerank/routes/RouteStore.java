package lk.routerank.routes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lk.routerank.roads.LatLon;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code route}, {@code route_waypoint}, {@code route_segment} and {@code slot_change} tables. */
@Repository
class RouteStore {

	private final JdbcClient jdbc;

	RouteStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Locks the user's row for the rest of the transaction, so two saves, reorders or removes from one account
	 * run one after the other (each sees the other's slots and segments).
	 */
	Optional<Account> lockAccount(long userId) {
		return jdbc.sql("SELECT live_at, banned_at FROM app_user WHERE id = :id FOR UPDATE")
			.param("id", userId)
			.query(Account.class)
			.optional();
	}

	Optional<Account> account(long userId) {
		return jdbc.sql("SELECT live_at, banned_at FROM app_user WHERE id = :id")
			.param("id", userId)
			.query(Account.class)
			.optional();
	}

	/** The user's routes that are not removed, by slot. */
	List<StoredRoute> active(long userId) {
		List<StoredRoute> routes = jdbc.sql("""
				SELECT id, slot, name, ST_Y(start_point) AS start_lat, ST_X(start_point) AS start_lon,
				       ST_Y(end_point) AS end_lat, ST_X(end_point) AS end_lon,
				       ST_AsText(geom_out) AS out_wkt, ST_AsText(geom_back) AS back_wkt,
				       length_out_m, length_back_m, created_at, updated_at
				FROM route WHERE user_id = :user AND removed_at IS NULL ORDER BY slot""")
			.param("user", userId)
			.query((rs, n) -> new StoredRoute(rs.getLong("id"), rs.getInt("slot"), rs.getString("name"),
					new LatLon(rs.getDouble("start_lat"), rs.getDouble("start_lon")),
					new LatLon(rs.getDouble("end_lat"), rs.getDouble("end_lon")), new ArrayList<>(),
					parseLine(rs.getString("out_wkt")), parseLine(rs.getString("back_wkt")), rs.getDouble("length_out_m"),
					rs.getDouble("length_back_m"), rs.getTimestamp("created_at").toInstant(),
					rs.getTimestamp("updated_at").toInstant()))
			.list();
		if (!routes.isEmpty()) {
			Map<Long, StoredRoute> byId = new HashMap<>();
			routes.forEach(r -> byId.put(r.id(), r));
			jdbc.sql("""
					SELECT route_id, ST_Y(point) AS lat, ST_X(point) AS lon FROM route_waypoint
					WHERE route_id = ANY(:ids) ORDER BY route_id, seq""")
				.param("ids", byId.keySet().toArray(Long[]::new))
				.query((rs, n) -> {
					byId.get(rs.getLong("route_id")).waypoints().add(new LatLon(rs.getDouble("lat"), rs.getDouble("lon")));
					return null;
				})
				.list();
		}
		return routes;
	}

	/** The start of each slot's current lock: its newest change in the last 24 hours. */
	Map<Integer, Instant> lockStarts(long userId, Instant now) {
		Map<Integer, Instant> starts = new HashMap<>();
		jdbc.sql("""
				SELECT slot, max(changed_at) AS started FROM slot_change
				WHERE user_id = :user AND changed_at > :since GROUP BY slot""")
			.param("user", userId)
			.param("since", java.sql.Timestamp.from(now.minus(Slots.LOCK)))
			.query((rs, n) -> starts.put(rs.getInt("slot"), rs.getTimestamp("started").toInstant()))
			.list();
		return starts;
	}

	void recordSlotChange(long userId, int slot, Instant at) {
		jdbc.sql("INSERT INTO slot_change (user_id, slot, changed_at) VALUES (:user, :slot, :at)")
			.param("user", userId)
			.param("slot", slot)
			.param("at", java.sql.Timestamp.from(at))
			.update();
	}

	/** The user's other routes that count for any of these segments, by slot. */
	List<Overlap> overlaps(long userId, Collection<Long> segmentIds, Long exceptRouteId) {
		if (segmentIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT DISTINCT r.id, r.slot, r.name FROM route r
				JOIN route_segment rs ON rs.route_id = r.id
				WHERE r.user_id = :user AND r.removed_at IS NULL AND rs.scores
				  AND rs.segment_id = ANY(:segments) AND r.id <> :except
				ORDER BY r.slot""")
			.param("user", userId)
			.param("segments", segmentIds.toArray(Long[]::new))
			.param("except", exceptRouteId == null ? -1L : exceptRouteId)
			.query(Overlap.class)
			.list();
	}

	long insert(long userId, int slot, NewRoute route, Instant now) {
		long id = jdbc.sql("""
				INSERT INTO route (user_id, slot, kind, name, start_point, end_point, geom_out, geom_back,
				                   length_out_m, length_back_m, created_at, updated_at)
				VALUES (:user, :slot, 'new', :name, ST_SetSRID(ST_MakePoint(:startLon, :startLat), 4326),
				        ST_SetSRID(ST_MakePoint(:endLon, :endLat), 4326), ST_GeomFromText(:out, 4326),
				        ST_GeomFromText(:back, 4326), :lengthOut, :lengthBack, :now, :now)
				RETURNING id""")
			.param("user", userId)
			.param("slot", slot)
			.params(routeParams(route, now))
			.query(Long.class)
			.single();
		writeParts(id, route);
		return id;
	}

	void update(long routeId, NewRoute route, Instant now) {
		jdbc.sql("""
				UPDATE route SET name = :name, start_point = ST_SetSRID(ST_MakePoint(:startLon, :startLat), 4326),
				       end_point = ST_SetSRID(ST_MakePoint(:endLon, :endLat), 4326), geom_out = ST_GeomFromText(:out, 4326),
				       geom_back = ST_GeomFromText(:back, 4326), length_out_m = :lengthOut, length_back_m = :lengthBack,
				       updated_at = :now
				WHERE id = :id""")
			.param("id", routeId)
			.params(routeParams(route, now))
			.update();
		jdbc.sql("DELETE FROM route_waypoint WHERE route_id = :id").param("id", routeId).update();
		jdbc.sql("DELETE FROM route_segment WHERE route_id = :id").param("id", routeId).update();
		writeParts(routeId, route);
	}

	void moveToSlot(long routeId, int slot, Instant now) {
		jdbc.sql("UPDATE route SET slot = :slot, updated_at = :now WHERE id = :id")
			.param("id", routeId)
			.param("slot", slot)
			.param("now", java.sql.Timestamp.from(now))
			.update();
	}

	/** Marks a route removed (never deleted, so it can be reviewed); it frees its slot and stops counting. */
	boolean remove(long userId, long routeId, Instant now) {
		return jdbc.sql("""
				UPDATE route SET removed_at = :now
				WHERE id = :id AND user_id = :user AND removed_at IS NULL""")
			.param("id", routeId)
			.param("user", userId)
			.param("now", java.sql.Timestamp.from(now))
			.update() == 1;
	}

	private Map<String, Object> routeParams(NewRoute route, Instant now) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("name", route.name());
		params.put("startLat", route.start().lat());
		params.put("startLon", route.start().lon());
		params.put("endLat", route.end().lat());
		params.put("endLon", route.end().lon());
		params.put("out", wkt(route.out()));
		params.put("back", wkt(route.back()));
		params.put("lengthOut", route.lengthOutM());
		params.put("lengthBack", route.lengthBackM());
		params.put("now", java.sql.Timestamp.from(now));
		return params;
	}

	private void writeParts(long routeId, NewRoute route) {
		for (int i = 0; i < route.waypoints().size(); i++) {
			LatLon p = route.waypoints().get(i);
			jdbc.sql("""
					INSERT INTO route_waypoint (route_id, seq, point)
					VALUES (:id, :seq, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326))""")
				.param("id", routeId)
				.param("seq", i)
				.param("lat", p.lat())
				.param("lon", p.lon())
				.update();
		}
		jdbc.sql("""
				INSERT INTO route_segment (route_id, segment_id, scores)
				SELECT :id, s, true FROM unnest(:segments::bigint[]) AS s""")
			.param("id", routeId)
			.param("segments", route.segmentIds().toArray(Long[]::new))
			.update();
	}

	static String wkt(List<LatLon> line) {
		StringBuilder wkt = new StringBuilder("LINESTRING(");
		for (int i = 0; i < line.size(); i++) {
			if (i > 0) {
				wkt.append(", ");
			}
			wkt.append(line.get(i).lon()).append(' ').append(line.get(i).lat());
		}
		return wkt.append(')').toString();
	}

	static List<LatLon> parseLine(String wkt) {
		String body = wkt.substring(wkt.indexOf('(') + 1, wkt.lastIndexOf(')'));
		List<LatLon> line = new ArrayList<>();
		for (String pair : body.split(",")) {
			String[] xy = pair.trim().split("\\s+");
			line.add(new LatLon(Double.parseDouble(xy[1]), Double.parseDouble(xy[0])));
		}
		return line;
	}

	record Account(Instant liveAt, Instant bannedAt) {
	}

	record Overlap(long id, int slot, String name) {
	}

	record StoredRoute(long id, int slot, String name, LatLon start, LatLon end, List<LatLon> waypoints,
			List<LatLon> out, List<LatLon> back, double lengthOutM, double lengthBackM, Instant createdAt,
			Instant updatedAt) {
	}

	/** A route worked out on the server, ready to store. */
	record NewRoute(String name, LatLon start, LatLon end, List<LatLon> waypoints, List<LatLon> out, List<LatLon> back,
			double lengthOutM, double lengthBackM, Collection<Long> segmentIds) {
	}

}
