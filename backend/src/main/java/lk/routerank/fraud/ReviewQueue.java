package lk.routerank.fraud;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The admin review queue: open flags grouped into clusters (one device, one burst, one honeypot hit), and the
 * accounts on hold. Releasing or removing works on a whole cluster at once; the admin module records an audit entry
 * per account and removes the votes.
 */
@Service
public class ReviewQueue {

	private final JdbcClient jdbc;

	private final Flags flags;

	ReviewQueue(JdbcClient jdbc, Flags flags) {
		this.jdbc = jdbc;
		this.flags = flags;
	}

	/** Open clusters, the most suspicious (highest trust score) first. */
	public List<Cluster> clusters() {
		Map<String, Cluster> clusters = new LinkedHashMap<>();
		jdbc.sql("""
				SELECT f.cluster_key, f.reason, f.signals::text AS signals, f.created_at AS flagged_at,
				       u.id, u.email, u.created_at, u.live_at, u.held_at, u.banned_at, u.trust_score,
				       (SELECT array_agg(o.reason ORDER BY o.reason) FROM fraud_flag o
				        WHERE o.user_id = u.id AND o.status = 'open') AS reasons
				FROM fraud_flag f JOIN app_user u ON u.id = f.user_id
				WHERE f.status = 'open'
				ORDER BY f.created_at, u.id""")
			.query((rs, n) -> {
				Account account = account(rs);
				String reason = rs.getString("reason");
				Instant flaggedAt = rs.getTimestamp("flagged_at").toInstant();
				clusters.computeIfAbsent(rs.getString("cluster_key"), key -> new Cluster(key, reason, flaggedAt, new ArrayList<>()))
					.accounts()
					.add(account);
				return null;
			})
			.list();
		return clusters.values()
			.stream()
			.sorted(Comparator.comparingDouble(Cluster::score).reversed().thenComparing(Cluster::flaggedAt))
			.toList();
	}

	/** Every account on hold, including ones whose flags were reviewed and whose votes were removed. */
	public List<Account> held() {
		return jdbc.sql("""
				SELECT u.id, u.email, u.created_at, u.live_at, u.held_at, u.banned_at, u.trust_score,
				       (SELECT array_agg(o.reason ORDER BY o.reason) FROM fraud_flag o
				        WHERE o.user_id = u.id AND o.status = 'open') AS reasons
				FROM app_user u WHERE u.held_at IS NOT NULL ORDER BY u.held_at DESC""")
			.query((rs, n) -> account(rs))
			.list();
	}

	/**
	 * Closes a cluster's open flags. Releasing lifts the hold from accounts with no other open flag; removing keeps
	 * them held (the admin module removes their votes).
	 *
	 * @return the accounts in the cluster
	 */
	public List<Long> resolveCluster(String clusterKey, Decision decision, long reviewerId, Instant now) {
		List<Long> users = jdbc.sql("""
				UPDATE fraud_flag SET status = :status, reviewed_by = :reviewer, reviewed_at = :now
				WHERE cluster_key = :cluster AND status = 'open' RETURNING user_id""")
			.param("status", decision.status)
			.param("reviewer", reviewerId)
			.param("now", Timestamp.from(now))
			.param("cluster", clusterKey)
			.query(Long.class)
			.list();
		for (long user : users) {
			if (decision == Decision.RELEASE) {
				liftHoldIfNothingOpen(user);
			}
			flags.updateTrustScore(user);
		}
		return users;
	}

	/**
	 * Releases one account: closes its open flags and lifts its hold.
	 *
	 * @return whether it was held
	 */
	public boolean release(long userId, long reviewerId, Instant now) {
		jdbc.sql("""
				UPDATE fraud_flag SET status = 'released', reviewed_by = :reviewer, reviewed_at = :now
				WHERE user_id = :user AND status = 'open'""")
			.param("reviewer", reviewerId)
			.param("now", Timestamp.from(now))
			.param("user", userId)
			.update();
		flags.updateTrustScore(userId);
		return jdbc.sql("UPDATE app_user SET held_at = NULL WHERE id = :user AND held_at IS NOT NULL")
			.param("user", userId)
			.update() == 1;
	}

	private void liftHoldIfNothingOpen(long userId) {
		jdbc.sql("""
				UPDATE app_user SET held_at = NULL
				WHERE id = :user AND NOT EXISTS (SELECT 1 FROM fraud_flag WHERE user_id = :user AND status = 'open')""")
			.param("user", userId)
			.update();
	}

	private static Account account(ResultSet rs) throws SQLException {
		Array reasons = rs.getArray("reasons");
		return new Account(rs.getLong("id"), rs.getString("email"), instant(rs, "created_at"), instant(rs, "live_at"),
				instant(rs, "held_at"), instant(rs, "banned_at"), rs.getDouble("trust_score"),
				reasons == null ? List.of() : Arrays.asList((String[]) reasons.getArray()));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp t = rs.getTimestamp(column);
		return t == null ? null : t.toInstant();
	}

	public enum Decision {

		RELEASE("released"), REMOVE("removed");

		private final String status;

		Decision(String status) {
			this.status = status;
		}

	}

	/**
	 * Flagged accounts that share a cause.
	 *
	 * @param key e.g. {@code device:3fa2c1…} or {@code burst:42} (the burst's lowest account ID)
	 * @param reason the trigger that made it: shared_device, burst or honeypot
	 */
	public record Cluster(String key, String reason, Instant flaggedAt, List<Account> accounts) {

		/** The highest trust score in the cluster, which sorts the queue. */
		public double score() {
			return accounts.stream().mapToDouble(Account::trustScore).max().orElse(0);
		}

	}

	/**
	 * @param trustScore the sum of its open flags' weights; only sorts the queue
	 * @param reasons its open flags' triggers
	 */
	public record Account(long id, String email, Instant createdAt, Instant liveAt, Instant heldAt, Instant bannedAt,
			double trustScore, List<String> reasons) {
	}

}
