package lk.routerank.scoring;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** The last scoring run's stretches, for other modules (My routes shows each route's busiest stretch). */
@Service
public class Stretches {

	private final JdbcClient jdbc;

	Stretches(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * A route's busiest stretch: of the stretches its scoring segments are in, the best-ranked one, else the one
	 * with the most points.
	 *
	 * @param slug its link, {@code /s/<slug>}
	 * @param rank overall rank, or null when it is not on the leaderboards
	 */
	public record Busiest(String slug, String name, int points, int people, Integer rank) {
	}

	/** By route ID; routes on no stretch yet (nothing scored there) are left out. */
	public Map<Long, Busiest> busiest(Collection<Long> routeIds) {
		Map<Long, Busiest> found = new HashMap<>();
		if (routeIds.isEmpty()) {
			return found;
		}
		jdbc.sql("""
				SELECT DISTINCT ON (rs.route_id) rs.route_id, s.slug, s.name, s.points, s.people, s.rank_overall
				FROM route_segment rs
				JOIN stretch s ON s.segment_ids @> ARRAY[rs.segment_id]
				WHERE rs.route_id = ANY(:ids::bigint[]) AND rs.scores
				ORDER BY rs.route_id, s.rank_overall NULLS LAST, s.points DESC, s.slug""")
			.param("ids", routeIds.toArray(Long[]::new))
			.query((rs, n) -> found.put(rs.getLong("route_id"), new Busiest(rs.getString("slug"), rs.getString("name"),
					rs.getInt("points"), rs.getInt("people"), (Integer) rs.getObject("rank_overall"))))
			.list();
		return found;
	}

}
