package lk.routerank.roads;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

/** The {@code place} table: OSM place names from the same import as the road segments. No external geocoder. */
class Places {

	private final JdbcClient jdbc;

	Places(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	String nearestName(long importRunId, LatLon point) {
		return jdbc.sql("""
				SELECT name FROM place WHERE import_run_id = :run
				ORDER BY geom <-> ST_SetSRID(ST_MakePoint(:lon, :lat), 4326) LIMIT 1""")
			.param("run", importRunId)
			.param("lat", point.lat())
			.param("lon", point.lon())
			.query(String.class)
			.optional()
			.orElse(null);
	}

	/**
	 * Towns, suburbs and quarters within {@code withinM} of a line, in the order the line passes them, each once:
	 * "Borella · Nugegoda · Maharagama" on a bus route's sheet (W05).
	 */
	List<String> along(long importRunId, String lineWkt, double withinM, int limit) {
		return jdbc.sql("""
				SELECT name FROM (
				    SELECT DISTINCT ON (p.name) p.name, ST_LineLocatePoint(l.geom, p.geom) AS at
				    FROM place p, (SELECT ST_GeomFromText(:wkt, 4326) AS geom) l
				    WHERE p.import_run_id = :run AND p.kind IN ('city', 'town', 'suburb', 'quarter')
				      AND ST_DWithin(p.geom::geography, l.geom::geography, :within)
				    ORDER BY p.name, at
				) passed ORDER BY at LIMIT :limit""")
			.param("run", importRunId)
			.param("wkt", lineWkt)
			.param("within", withinM)
			.param("limit", limit)
			.query(String.class)
			.list();
	}

	/**
	 * Names starting with the text (case-insensitive), biggest places first: cities, towns, suburbs, quarters,
	 * neighbourhoods, villages. Aliases match too ("Pitakotuwa" finds Pettah); results show the main name.
	 */
	List<Place> search(long importRunId, String prefix, int limit) {
		String escaped = prefix.strip().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
		if (escaped.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT name, kind, ST_Y(geom) AS lat, ST_X(geom) AS lon FROM place
				WHERE import_run_id = :run AND (lower(name) LIKE :prefix ESCAPE '\\'
				      OR EXISTS (SELECT 1 FROM unnest(aliases) a WHERE lower(a) LIKE :prefix ESCAPE '\\'))
				ORDER BY array_position(ARRAY['city', 'town', 'suburb', 'quarter', 'neighbourhood', 'village'], kind), name
				LIMIT :limit""")
			.param("run", importRunId)
			.param("prefix", escaped + "%")
			.param("limit", limit)
			.query(Place.class)
			.list();
	}

}
