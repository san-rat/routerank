package lk.routerank.busroutes;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import lk.routerank.roads.LatLon;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code bus_route}, {@code bus_route_waypoint} and {@code bus_route_segment} tables. */
@Repository
class BusRouteStore {

	private final JdbcClient jdbc;

	BusRouteStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Bus routes that aren't retired, with their lines and segments, for the extension check. */
	List<ExtensionCheck.Bus> active() {
		Map<Long, Set<Long>> segments = new HashMap<>();
		jdbc.sql("""
				SELECT bs.bus_route_id, bs.segment_id FROM bus_route_segment bs
				JOIN bus_route b ON b.id = bs.bus_route_id WHERE b.retired_at IS NULL""")
			.query((ResultSet rs) -> {
				segments.computeIfAbsent(rs.getLong(1), k -> new HashSet<>()).add(rs.getLong(2));
			});
		return jdbc.sql("""
				SELECT id, number, start_name, end_name, ST_Y(start_point) AS start_lat, ST_X(start_point) AS start_lon,
				       ST_Y(end_point) AS end_lat, ST_X(end_point) AS end_lon, ST_AsText(geom_out) AS out_wkt,
				       ST_AsText(geom_back) AS back_wkt, length_out_m, length_back_m
				FROM bus_route WHERE retired_at IS NULL ORDER BY id""")
			.query((rs, n) -> new ExtensionCheck.Bus(rs.getLong("id"), rs.getString("number"),
					rs.getString("start_name"), rs.getString("end_name"), point(rs, "start"), point(rs, "end"),
					parseLine(rs.getString("out_wkt")), parseLine(rs.getString("back_wkt")), rs.getDouble("length_out_m"),
					rs.getDouble("length_back_m"), segments.getOrDefault(rs.getLong("id"), Set.of())))
			.list();
	}

	/** Every bus route, retired ones too, newest first within each, with how many routes extend it. */
	List<Listed> all() {
		Map<Long, List<LatLon>> waypoints = waypoints();
		return jdbc.sql("""
				SELECT b.id, b.number, b.start_name, b.end_name, ST_Y(b.start_point) AS start_lat,
				       ST_X(b.start_point) AS start_lon, ST_Y(b.end_point) AS end_lat, ST_X(b.end_point) AS end_lon,
				       ST_AsText(b.geom_out) AS out_wkt, ST_AsText(b.geom_back) AS back_wkt, b.length_out_m,
				       b.length_back_m, b.towns, b.created_at, b.updated_at, b.retired_at,
				       (SELECT count(*) FROM route r WHERE r.bus_route_id = b.id AND r.removed_at IS NULL) AS extensions
				FROM bus_route b ORDER BY b.retired_at IS NOT NULL, b.number, b.id""")
			.query((rs, n) -> new Listed(rs.getLong("id"), rs.getString("number"), rs.getString("start_name"),
					rs.getString("end_name"), point(rs, "start"), point(rs, "end"),
					waypoints.getOrDefault(rs.getLong("id"), List.of()), parseLine(rs.getString("out_wkt")),
					parseLine(rs.getString("back_wkt")), rs.getDouble("length_out_m"), rs.getDouble("length_back_m"),
					strings(rs.getArray("towns")), instant(rs, "created_at"), instant(rs, "updated_at"),
					instant(rs, "retired_at"), rs.getInt("extensions")))
			.list();
	}

	Optional<Listed> find(long id) {
		return all().stream().filter(b -> b.id() == id).findFirst();
	}

	boolean numberTaken(String number, Long exceptId) {
		return jdbc.sql("""
				SELECT count(*) FROM bus_route WHERE number = :number AND retired_at IS NULL AND id <> :except""")
			.param("number", number)
			.param("except", exceptId == null ? -1L : exceptId)
			.query(Integer.class)
			.single() > 0;
	}

	/** Routes (not removed) saved as extensions of this bus. */
	int extensionCount(long id) {
		return jdbc.sql("SELECT count(*) FROM route WHERE bus_route_id = :id AND removed_at IS NULL")
			.param("id", id)
			.query(Integer.class)
			.single();
	}

	long insert(Drawn bus, Instant now) {
		long id = jdbc.sql("""
				INSERT INTO bus_route (number, name, start_name, end_name, start_point, end_point, geom_out, geom_back,
				                       length_out_m, length_back_m, towns, created_at, updated_at)
				VALUES (:number, :name, :startName, :endName, ST_SetSRID(ST_MakePoint(:startLon, :startLat), 4326),
				        ST_SetSRID(ST_MakePoint(:endLon, :endLat), 4326), ST_GeomFromText(:out, 4326),
				        ST_GeomFromText(:back, 4326), :lengthOut, :lengthBack, :towns, :now, :now)
				RETURNING id""")
			.params(params(bus, now))
			.query(Long.class)
			.single();
		writeParts(id, bus);
		return id;
	}

	void update(long id, Drawn bus, Instant now) {
		jdbc.sql("""
				UPDATE bus_route SET number = :number, name = :name, start_name = :startName, end_name = :endName,
				       start_point = ST_SetSRID(ST_MakePoint(:startLon, :startLat), 4326),
				       end_point = ST_SetSRID(ST_MakePoint(:endLon, :endLat), 4326), geom_out = ST_GeomFromText(:out, 4326),
				       geom_back = ST_GeomFromText(:back, 4326), length_out_m = :lengthOut, length_back_m = :lengthBack,
				       towns = :towns, updated_at = :now
				WHERE id = :id""")
			.param("id", id)
			.params(params(bus, now))
			.update();
		jdbc.sql("DELETE FROM bus_route_waypoint WHERE bus_route_id = :id").param("id", id).update();
		jdbc.sql("DELETE FROM bus_route_segment WHERE bus_route_id = :id").param("id", id).update();
		writeParts(id, bus);
	}

	boolean retire(long id, Instant now) {
		return jdbc.sql("UPDATE bus_route SET retired_at = :now, updated_at = :now WHERE id = :id AND retired_at IS NULL")
			.param("id", id)
			.param("now", Timestamp.from(now))
			.update() == 1;
	}

	private Map<String, Object> params(Drawn bus, Instant now) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("number", bus.number());
		params.put("name", bus.startName() + " → " + bus.endName());
		params.put("startName", bus.startName());
		params.put("endName", bus.endName());
		params.put("startLat", bus.start().lat());
		params.put("startLon", bus.start().lon());
		params.put("endLat", bus.end().lat());
		params.put("endLon", bus.end().lon());
		params.put("out", wkt(bus.out()));
		params.put("back", wkt(bus.back()));
		params.put("lengthOut", bus.lengthOutM());
		params.put("lengthBack", bus.lengthBackM());
		params.put("towns", bus.towns().toArray(String[]::new));
		params.put("now", Timestamp.from(now));
		return params;
	}

	private void writeParts(long id, Drawn bus) {
		for (int i = 0; i < bus.waypoints().size(); i++) {
			LatLon p = bus.waypoints().get(i);
			jdbc.sql("""
					INSERT INTO bus_route_waypoint (bus_route_id, seq, point)
					VALUES (:id, :seq, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326))""")
				.param("id", id)
				.param("seq", i)
				.param("lat", p.lat())
				.param("lon", p.lon())
				.update();
		}
		jdbc.sql("""
				INSERT INTO bus_route_segment (bus_route_id, segment_id)
				SELECT :id, s FROM unnest(:segments::bigint[]) AS s""")
			.param("id", id)
			.param("segments", bus.segmentIds().toArray(Long[]::new))
			.update();
	}

	private Map<Long, List<LatLon>> waypoints() {
		Map<Long, List<LatLon>> waypoints = new HashMap<>();
		jdbc.sql("""
				SELECT bus_route_id, ST_Y(point) AS lat, ST_X(point) AS lon FROM bus_route_waypoint
				ORDER BY bus_route_id, seq""")
			.query((ResultSet rs) -> {
				waypoints.computeIfAbsent(rs.getLong("bus_route_id"), k -> new ArrayList<>())
					.add(new LatLon(rs.getDouble("lat"), rs.getDouble("lon")));
			});
		return waypoints;
	}

	private static LatLon point(ResultSet rs, String prefix) throws SQLException {
		return new LatLon(rs.getDouble(prefix + "_lat"), rs.getDouble(prefix + "_lon"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp t = rs.getTimestamp(column);
		return t == null ? null : t.toInstant();
	}

	private static List<String> strings(Array array) throws SQLException {
		return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
	}

	static String wkt(List<LatLon> line) {
		StringBuilder wkt = new StringBuilder("LINESTRING(");
		for (int i = 0; i < line.size(); i++) {
			wkt.append(i > 0 ? ", " : "").append(line.get(i).lon()).append(' ').append(line.get(i).lat());
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

	/** A bus route worked out on the server from the admin's points, ready to store. */
	record Drawn(String number, String startName, String endName, LatLon start, LatLon end, List<LatLon> waypoints,
			List<LatLon> out, List<LatLon> back, double lengthOutM, double lengthBackM, List<String> towns,
			Collection<Long> segmentIds) {
	}

	/** A stored bus route as the admin pages list it. */
	record Listed(long id, String number, String startName, String endName, LatLon start, LatLon end,
			List<LatLon> waypoints, List<LatLon> out, List<LatLon> back, double lengthOutM, double lengthBackM,
			List<String> towns, Instant createdAt, Instant updatedAt, Instant retiredAt, int extensions) {
	}

}
