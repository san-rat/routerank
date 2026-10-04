package lk.routerank.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** What the admin pages change directly: votes ({@code route.removed_at}) and bans ({@code app_user.banned_at}). */
@Repository
class AdminStore {

	private final JdbcClient jdbc;

	AdminStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** The accounts' routes, removed ones too, by account then slot. */
	List<RouteSummary> routesOf(Collection<Long> userIds) {
		if (userIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT r.id, r.user_id, r.slot, r.name, r.kind, b.number AS bus_number,
				       greatest(r.length_out_m, r.length_back_m) AS length_m, r.created_at, r.updated_at, r.removed_at
				FROM route r LEFT JOIN bus_route b ON b.id = r.bus_route_id
				WHERE r.user_id = ANY(:users::bigint[])
				ORDER BY r.user_id, r.removed_at IS NOT NULL, r.slot, r.id""")
			.param("users", userIds.toArray(Long[]::new))
			.query((rs, n) -> new RouteSummary(rs.getLong("id"), rs.getLong("user_id"), rs.getInt("slot"),
					rs.getString("name"), rs.getString("kind"), rs.getString("bus_number"), rs.getDouble("length_m"),
					rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
					instant(rs.getTimestamp("removed_at"))))
			.list();
	}

	Optional<AccountRow> account(long userId) {
		return jdbc.sql("""
				SELECT id, email, role, created_at, live_at, held_at, banned_at, trust_score FROM app_user WHERE id = :id""")
			.param("id", userId)
			.query((rs, n) -> new AccountRow(rs.getLong("id"), rs.getString("email"), rs.getString("role"),
					rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("live_at").toInstant(),
					instant(rs.getTimestamp("held_at")), instant(rs.getTimestamp("banned_at")), rs.getDouble("trust_score")))
			.optional();
	}

	/** Finds accounts by email (prefix, case-insensitive) or by ID. */
	List<AccountRow> find(String query) {
		String q = query.strip().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
		return jdbc.sql("""
				SELECT id, email, role, created_at, live_at, held_at, banned_at, trust_score FROM app_user
				WHERE lower(email) LIKE :prefix ESCAPE '\\' OR id::text = :exact
				ORDER BY created_at DESC LIMIT 20""")
			.param("prefix", q + "%")
			.param("exact", query.strip())
			.query((rs, n) -> new AccountRow(rs.getLong("id"), rs.getString("email"), rs.getString("role"),
					rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("live_at").toInstant(),
					instant(rs.getTimestamp("held_at")), instant(rs.getTimestamp("banned_at")), rs.getDouble("trust_score")))
			.list();
	}

	/** Removes all the account's votes that aren't removed yet; their points come off at the next scoring run. */
	List<Long> removeRoutes(long userId, Instant now) {
		return jdbc.sql("""
				UPDATE route SET removed_at = :now WHERE user_id = :user AND removed_at IS NULL RETURNING id""")
			.param("user", userId)
			.param("now", Timestamp.from(now))
			.query(Long.class)
			.list();
	}

	Optional<RouteSummary> route(long routeId) {
		return jdbc.sql("""
				SELECT r.id, r.user_id, r.slot, r.name, r.kind, b.number AS bus_number,
				       greatest(r.length_out_m, r.length_back_m) AS length_m, r.created_at, r.updated_at, r.removed_at
				FROM route r LEFT JOIN bus_route b ON b.id = r.bus_route_id WHERE r.id = :id""")
			.param("id", routeId)
			.query((rs, n) -> new RouteSummary(rs.getLong("id"), rs.getLong("user_id"), rs.getInt("slot"),
					rs.getString("name"), rs.getString("kind"), rs.getString("bus_number"), rs.getDouble("length_m"),
					rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
					instant(rs.getTimestamp("removed_at"))))
			.optional();
	}

	boolean removeRoute(long routeId, Instant now) {
		return jdbc.sql("UPDATE route SET removed_at = :now WHERE id = :id AND removed_at IS NULL")
			.param("id", routeId)
			.param("now", Timestamp.from(now))
			.update() == 1;
	}

	/** Puts a removed vote back, unless its owner has since filled that slot. */
	boolean restoreRoute(long routeId) {
		return jdbc.sql("""
				UPDATE route r SET removed_at = NULL
				WHERE r.id = :id AND r.removed_at IS NOT NULL
				  AND NOT EXISTS (SELECT 1 FROM route o WHERE o.user_id = r.user_id AND o.slot = r.slot
				                  AND o.removed_at IS NULL)""")
			.param("id", routeId)
			.update() == 1;
	}

	boolean ban(long userId, Instant now) {
		return jdbc.sql("UPDATE app_user SET banned_at = :now WHERE id = :id AND banned_at IS NULL AND role <> 'admin'")
			.param("id", userId)
			.param("now", Timestamp.from(now))
			.update() == 1;
	}

	boolean unban(long userId) {
		return jdbc.sql("UPDATE app_user SET banned_at = NULL WHERE id = :id AND banned_at IS NOT NULL")
			.param("id", userId)
			.update() == 1;
	}

	private static Instant instant(Timestamp t) {
		return t == null ? null : t.toInstant();
	}

	/**
	 * A vote as the admin pages list it.
	 *
	 * @param kind "new" or "extension"
	 * @param busNumber for an extension, the bus route it extends
	 * @param lengthM the longer direction's length
	 */
	record RouteSummary(long id, long userId, int slot, String name, String kind, String busNumber, double lengthM,
			Instant createdAt, Instant updatedAt, Instant removedAt) {
	}

	record AccountRow(long id, String email, String role, Instant createdAt, Instant liveAt, Instant heldAt,
			Instant bannedAt, double trustScore) {
	}

}
