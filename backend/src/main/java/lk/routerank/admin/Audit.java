package lk.routerank.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * The append-only audit log: who did what, when, with the before and after values and the admin's written reason.
 * The database refuses updates and deletes on it (a trigger), and role changes made by hand are logged by another.
 */
@Service
public class Audit {

	private final JdbcClient jdbc;

	private final JsonMapper json;

	Audit(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/**
	 * @param action e.g. {@code bus_route.add}, {@code account.ban}
	 * @param target e.g. {@code bus_route:4}, {@code account:12}
	 * @param before the values before the action (any JSON-able value), or null
	 * @param after the values after it, or null
	 */
	public void record(long actorId, String action, String target, Object before, Object after, String reason,
			Instant at) {
		jdbc.sql("""
				INSERT INTO audit_log (actor_id, action, target, before, after, reason, at)
				VALUES (:actor, :action, :target, :before::jsonb, :after::jsonb, :reason, :at)""")
			.param("actor", actorId)
			.param("action", action)
			.param("target", target)
			.param("before", before == null ? null : json.writeValueAsString(before))
			.param("after", after == null ? null : json.writeValueAsString(after))
			.param("reason", reason)
			.param("at", Timestamp.from(at))
			.update();
	}

	/** The newest entries first. */
	List<Entry> recent(int limit, Long beforeId) {
		return jdbc.sql("""
				SELECT a.id, a.actor_id, u.email AS actor_email, a.action, a.target, a.before::text AS before,
				       a.after::text AS after, a.reason, a.at
				FROM audit_log a LEFT JOIN app_user u ON u.id = a.actor_id
				WHERE a.id < :before ORDER BY a.id DESC LIMIT :limit""")
			.param("before", beforeId == null ? Long.MAX_VALUE : beforeId)
			.param("limit", limit)
			.query((rs, n) -> new Entry(rs.getLong("id"), (Long) rs.getObject("actor_id"), rs.getString("actor_email"),
					rs.getString("action"), rs.getString("target"), rs.getString("before"), rs.getString("after"),
					rs.getString("reason"), rs.getTimestamp("at").toInstant()))
			.list();
	}

	/**
	 * @param actorId null for changes made in the database by hand
	 * @param before JSON text, or null
	 */
	record Entry(long id, Long actorId, String actorEmail, String action, String target, String before, String after,
			String reason, Instant at) {
	}

}
