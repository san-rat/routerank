package lk.routerank.roads;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.graphhopper.ResponsePath;
import com.graphhopper.util.PointList;
import com.graphhopper.util.details.PathDetail;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Maps GraphHopper paths onto {@code road_segment} rows by OSM way ID (ADR 0001): for each stretch of a path on
 * one way, that way's segments within 5 m of it count if the stretch covers more than half of them. Each
 * direction is judged on its own, and a segment either direction covers counts once.
 */
class SegmentMatcher {

	static final double BUFFER_M = 5;

	static final double MIN_COVER = 0.5;

	private final JdbcClient jdbc;

	SegmentMatcher(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Set<Long> match(long importRunId, ResponsePath out, ResponsePath back) {
		List<Short> dirs = new ArrayList<>();
		List<Long> ways = new ArrayList<>();
		List<String> lines = new ArrayList<>();
		addPieces((short) 0, out, dirs, ways, lines);
		if (back != null) {
			addPieces((short) 1, back, dirs, ways, lines);
		}
		if (ways.isEmpty()) {
			return Set.of();
		}
		List<Long> ids = jdbc.sql("""
				WITH piece AS (
				    SELECT p.dir, p.way_id, ST_Union(ST_GeomFromText(p.wkt, 4326)) AS geom
				    FROM unnest(:dirs::smallint[], :ways::bigint[], :wkts::text[]) AS p(dir, way_id, wkt)
				    GROUP BY p.dir, p.way_id
				)
				SELECT DISTINCT s.id
				FROM piece p
				JOIN road_segment s ON s.import_run_id = :run AND s.osm_way_id = p.way_id
				WHERE ST_DWithin(s.geom::geography, p.geom::geography, :buffer)
				  AND ST_Length(ST_Intersection(s.geom::geography, ST_Buffer(p.geom::geography, :buffer)))
				      > :cover * s.length_m""")
			.param("dirs", dirs.toArray(Short[]::new))
			.param("ways", ways.toArray(Long[]::new))
			.param("wkts", lines.toArray(String[]::new))
			.param("run", importRunId)
			.param("buffer", BUFFER_M)
			.param("cover", MIN_COVER)
			.query(Long.class)
			.list();
		return new HashSet<>(ids);
	}

	/** One WKT line per stretch of the path on a single OSM way. */
	private static void addPieces(short dir, ResponsePath path, List<Short> dirs, List<Long> ways, List<String> lines) {
		PointList points = path.getPoints();
		for (PathDetail detail : path.getPathDetails().getOrDefault("osm_way_id", List.of())) {
			if (!(detail.getValue() instanceof Number way) || detail.getLast() <= detail.getFirst()) {
				continue;
			}
			StringBuilder wkt = new StringBuilder("LINESTRING(");
			for (int i = detail.getFirst(); i <= detail.getLast(); i++) {
				if (i > detail.getFirst()) {
					wkt.append(", ");
				}
				wkt.append(points.getLon(i)).append(' ').append(points.getLat(i));
			}
			dirs.add(dir);
			ways.add(way.longValue());
			lines.add(wkt.append(')').toString());
		}
	}

}
