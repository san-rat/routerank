package lk.routerank.scoring;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lk.routerank.scoring.Rankings.Link;
import lk.routerank.scoring.Rankings.Stretch;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the scoring job reads (routes, segments, places) and writes ({@code segment_score}, {@code stretch},
 * {@code stretch_link}, {@code published_file}, {@code publish_usage}).
 */
@Repository
class ScoreStore {

	private final JdbcClient jdbc;

	private final JsonMapper json;

	ScoreStore(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/** The newest finished import: routes are matched against its segments. */
	Optional<Long> currentImport() {
		return jdbc.sql("SELECT id FROM import_run WHERE finished_at IS NOT NULL ORDER BY id DESC LIMIT 1")
			.query(Long.class)
			.optional();
	}

	/**
	 * Points and people per segment of the import: 3 / 2 / 1 from every route that is not removed or held, of an
	 * account past its 24-hour delay and not banned. A route lists each segment once whichever direction uses
	 * it, and only the scoring part of an extension counts.
	 */
	List<ScoredSegment> scoredSegments(long importRunId, Instant now) {
		return jdbc.sql("""
				WITH counted AS (
				    SELECT rs.segment_id, sum(4 - r.slot) AS points, count(DISTINCT r.user_id) AS people,
				           count(*) FILTER (WHERE r.slot = 1) AS first, count(*) FILTER (WHERE r.slot = 2) AS second,
				           count(*) FILTER (WHERE r.slot = 3) AS third
				    FROM route r
				    JOIN app_user u ON u.id = r.user_id
				    JOIN route_segment rs ON rs.route_id = r.id AND rs.scores
				    WHERE r.removed_at IS NULL AND r.held_at IS NULL
				      AND u.banned_at IS NULL AND u.live_at <= :now
				    GROUP BY rs.segment_id
				)
				SELECT s.id, s.province, s.is_link, s.name, s.ref, s.length_m, c.points, c.people, c.first, c.second, c.third,
				       ST_X(ST_StartPoint(s.geom)) AS from_lon, ST_Y(ST_StartPoint(s.geom)) AS from_lat,
				       ST_X(ST_EndPoint(s.geom)) AS to_lon, ST_Y(ST_EndPoint(s.geom)) AS to_lat,
				       ST_AsGeoJSON(s.geom, 5) AS line
				FROM counted c
				JOIN road_segment s ON s.id = c.segment_id AND s.import_run_id = :run
				ORDER BY s.id""")
			.param("run", importRunId)
			.param("now", java.sql.Timestamp.from(now))
			.query((rs, n) -> new ScoredSegment(rs.getLong("id"), rs.getString("province"), rs.getBoolean("is_link"),
					rs.getString("name"), rs.getString("ref"), rs.getDouble("length_m"), rs.getInt("points"),
					rs.getInt("people"), new int[] { rs.getInt("first"), rs.getInt("second"), rs.getInt("third") },
					ScoredSegment.Node.at(rs.getDouble("from_lon"), rs.getDouble("from_lat")),
					ScoredSegment.Node.at(rs.getDouble("to_lon"), rs.getDouble("to_lat")), coordinates(rs.getString("line"))))
			.list();
	}

	private double[][] coordinates(String geoJson) {
		JsonNode coords = json.readTree(geoJson).get("coordinates");
		double[][] line = new double[coords.size()][];
		for (int i = 0; i < coords.size(); i++) {
			line[i] = new double[] { coords.get(i).get(0).asDouble(), coords.get(i).get(1).asDouble() };
		}
		return line;
	}

	/**
	 * Pairs of scored one-way segments that are the two carriageways of one road: the same road (number, or name
	 * when either has none), and one's midpoint within 40 m of the other.
	 */
	List<long[]> parallelPairs(List<ScoredSegment> segments) {
		if (segments.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				WITH s AS (SELECT id, geom, name, ref FROM road_segment WHERE id = ANY(:ids::bigint[]) AND oneway <> 0
				           AND NOT is_link)
				SELECT a.id AS a, b.id AS b
				FROM s a
				JOIN s b ON a.id < b.id
				 AND ST_DWithin(b.geom, ST_LineInterpolatePoint(a.geom, 0.5), 0.0005)
				 AND ST_DWithin(b.geom::geography, ST_LineInterpolatePoint(a.geom, 0.5)::geography, 40)
				 AND NOT ST_Touches(a.geom, b.geom)
				 AND CASE WHEN a.ref IS NOT NULL AND b.ref IS NOT NULL THEN a.ref = b.ref
				          ELSE a.name IS NOT DISTINCT FROM b.name AND a.ref IS NOT DISTINCT FROM b.ref END
				ORDER BY a.id, b.id""")
			.param("ids", segments.stream().map(ScoredSegment::id).toArray(Long[]::new))
			.query((rs, n) -> new long[] { rs.getLong("a"), rs.getLong("b") })
			.list();
	}

	/** Every province with main roads, so a province with no votes still gets a (empty) entry. */
	List<String> provinces(long importRunId) {
		return jdbc.sql("SELECT DISTINCT province FROM road_segment WHERE import_run_id = :run ORDER BY province")
			.param("run", importRunId)
			.query(String.class)
			.list();
	}

	/** Distinct voters whose counted routes score in each province (W11: "4,812 people voted"). */
	Map<String, Integer> peopleByProvince(long importRunId, Instant now) {
		Map<String, Integer> people = new HashMap<>();
		jdbc.sql("""
				SELECT s.province, count(DISTINCT r.user_id) AS people
				FROM route r
				JOIN app_user u ON u.id = r.user_id
				JOIN route_segment rs ON rs.route_id = r.id AND rs.scores
				JOIN road_segment s ON s.id = rs.segment_id AND s.import_run_id = :run
				WHERE r.removed_at IS NULL AND r.held_at IS NULL AND u.banned_at IS NULL AND u.live_at <= :now
				GROUP BY s.province""")
			.param("run", importRunId)
			.param("now", java.sql.Timestamp.from(now))
			.query((rs, n) -> people.put(rs.getString("province"), rs.getInt("people")))
			.list();
		return people;
	}

	/** The nearest place name to each point (null where the import has no places), in the same order. */
	List<String> nearestPlaces(long importRunId, List<ScoredSegment.Node> points) {
		if (points.isEmpty()) {
			return List.of();
		}
		List<String> names = new ArrayList<>();
		jdbc.sql("""
				SELECT (SELECT name FROM place WHERE import_run_id = :run
				        ORDER BY geom <-> ST_SetSRID(ST_MakePoint(p.lon, p.lat), 4326) LIMIT 1) AS name
				FROM unnest(:lons::float8[], :lats::float8[]) WITH ORDINALITY AS p(lon, lat, i)
				ORDER BY p.i""")
			.param("run", importRunId)
			.param("lons", points.stream().map(ScoredSegment.Node::lon).toArray(Double[]::new))
			.param("lats", points.stream().map(ScoredSegment.Node::lat).toArray(Double[]::new))
			.query((rs, n) -> names.add(rs.getString("name")))
			.list();
		return names;
	}

	/** Existing stretch links, oldest first. */
	List<Link> links() {
		return jdbc.sql("SELECT slug, anchor_segment_id FROM stretch_link ORDER BY created_at, slug")
			.query(Link.class)
			.list();
	}

	/** Replaces the scoring output with this run's: segment scores, stretches, and new links. */
	void save(List<ScoredSegment> segments, List<Stretch> stretches, List<Link> newLinks, Instant now) {
		jdbc.sql("DELETE FROM segment_score").update();
		if (!segments.isEmpty()) {
			jdbc.sql("""
					INSERT INTO segment_score (segment_id, points, people, computed_at)
					SELECT * , :now FROM unnest(:ids::bigint[], :points::int[], :people::int[])""")
				.param("ids", segments.stream().map(ScoredSegment::id).toArray(Long[]::new))
				.param("points", segments.stream().map(ScoredSegment::points).toArray(Integer[]::new))
				.param("people", segments.stream().map(ScoredSegment::people).toArray(Integer[]::new))
				.param("now", java.sql.Timestamp.from(now))
				.update();
		}
		jdbc.sql("DELETE FROM stretch").update();
		for (Stretch s : stretches) {
			jdbc.sql("""
					INSERT INTO stretch (slug, name, province, segment_ids, length_m, points, people, rank_overall,
					                     rank_province)
					VALUES (:slug, :name, :province, :segments::bigint[], :length, :points, :people, :rankOverall,
					        :rankProvince)""")
				.param("slug", s.slug())
				.param("name", s.name())
				.param("province", s.province())
				.param("segments", s.segmentIds().toArray(Long[]::new))
				.param("length", s.lengthM())
				.param("points", s.points())
				.param("people", s.people())
				.param("rankOverall", s.rankOverall())
				.param("rankProvince", s.rankProvince())
				.update();
		}
		for (Link link : newLinks) {
			jdbc.sql("INSERT INTO stretch_link (slug, anchor_segment_id, created_at) VALUES (:slug, :anchor, :now)")
				.param("slug", link.slug())
				.param("anchor", link.anchorSegmentId())
				.param("now", java.sql.Timestamp.from(now))
				.update();
		}
	}

	/** Which of these R2 keys are already uploaded. */
	List<String> published(Collection<String> keys) {
		return jdbc.sql("SELECT key FROM published_file WHERE key = ANY(:keys::text[])")
			.param("keys", keys.toArray(String[]::new))
			.query(String.class)
			.list();
	}

	void recordUpload(String key, Instant now) {
		jdbc.sql("""
				INSERT INTO published_file (key, first_published_at, last_referenced_at) VALUES (:key, :now, :now)
				ON CONFLICT (key) DO UPDATE SET last_referenced_at = EXCLUDED.last_referenced_at""")
			.param("key", key)
			.param("now", java.sql.Timestamp.from(now))
			.update();
	}

	void markReferenced(Collection<String> keys, Instant now) {
		jdbc.sql("UPDATE published_file SET last_referenced_at = :now WHERE key = ANY(:keys::text[])")
			.param("keys", keys.toArray(String[]::new))
			.param("now", java.sql.Timestamp.from(now))
			.update();
	}

	/** Files no manifest has referenced since the cutoff. */
	List<String> unreferencedSince(Instant cutoff) {
		return jdbc.sql("SELECT key FROM published_file WHERE last_referenced_at < :cutoff ORDER BY key")
			.param("cutoff", java.sql.Timestamp.from(cutoff))
			.query(String.class)
			.list();
	}

	void forget(String key) {
		jdbc.sql("DELETE FROM published_file WHERE key = :key").param("key", key).update();
	}

	/** R2 writes so far in the month (UTC) of the given time. */
	int writes(Instant now) {
		return jdbc.sql("SELECT coalesce((SELECT writes FROM publish_usage WHERE month = :month), 0)")
			.param("month", month(now))
			.query(Integer.class)
			.single();
	}

	void addWrites(Instant now, int count) {
		jdbc.sql("""
				INSERT INTO publish_usage (month, writes) VALUES (:month, :count)
				ON CONFLICT (month) DO UPDATE SET writes = publish_usage.writes + EXCLUDED.writes""")
			.param("month", month(now))
			.param("count", count)
			.update();
	}

	private static LocalDate month(Instant now) {
		return now.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
	}

}
