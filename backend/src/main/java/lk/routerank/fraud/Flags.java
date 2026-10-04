package lk.routerank.fraud;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** The {@code fraud_flag} and {@code device_signal} tables, and the holds and trust scores on {@code app_user}. */
@Repository
class Flags {

	enum Reason {

		SHARED_DEVICE, BURST, HONEYPOT;

		String db() {
			return name().toLowerCase(Locale.ROOT);
		}

	}

	private final JdbcClient jdbc;

	private final JsonMapper json;

	Flags(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/**
	 * Flags an account and holds it. An account is flagged once per reason, ever: one an admin released isn't
	 * flagged again for the same trigger.
	 *
	 * @return whether this is a new flag
	 */
	boolean flag(long userId, Reason reason, String clusterKey, Map<String, Object> signals, double weight, Instant now) {
		boolean added = jdbc.sql("""
				INSERT INTO fraud_flag (user_id, reason, cluster_key, signals, weight, created_at)
				VALUES (:user, :reason, :cluster, :signals::jsonb, :weight, :now)
				ON CONFLICT (user_id, reason) DO NOTHING""")
			.param("user", userId)
			.param("reason", reason.db())
			.param("cluster", clusterKey)
			.param("signals", json.writeValueAsString(signals))
			.param("weight", weight)
			.param("now", Timestamp.from(now))
			.update() == 1;
		if (added) {
			jdbc.sql("UPDATE app_user SET held_at = coalesce(held_at, :now) WHERE id = :user")
				.param("user", userId)
				.param("now", Timestamp.from(now))
				.update();
			updateTrustScore(userId);
		}
		return added;
	}

	/** The trust score is the sum of the account's open flags' weights; it only sorts the review queue. */
	void updateTrustScore(long userId) {
		jdbc.sql("""
				UPDATE app_user SET trust_score = (
				    SELECT coalesce(sum(weight), 0) FROM fraud_flag WHERE user_id = :user AND status = 'open')
				WHERE id = :user""")
			.param("user", userId)
			.update();
	}

	/** Accounts with an open flag for some reason other than this one. */
	Set<Long> flaggedForOtherReasons(Reason reason) {
		return Set.copyOf(jdbc.sql("SELECT DISTINCT user_id FROM fraud_flag WHERE status = 'open' AND reason <> :reason")
			.param("reason", reason.db())
			.query(Long.class)
			.list());
	}

	/** Devices with at least this many accounts on them. */
	List<Device> sharedDevices(int minAccounts) {
		return jdbc.sql("""
				SELECT device_hash, array_agg(DISTINCT user_id ORDER BY user_id) AS users FROM device_signal
				GROUP BY device_hash HAVING count(DISTINCT user_id) >= :min""")
			.param("min", minAccounts)
			.query((rs, n) -> new Device(rs.getBytes("device_hash"),
					Arrays.asList((Long[]) rs.getArray("users").getArray())))
			.list();
	}

	List<Long> accountsOn(byte[] deviceHash) {
		return jdbc.sql("SELECT DISTINCT user_id FROM device_signal WHERE device_hash = :hash ORDER BY user_id")
			.param("hash", deviceHash)
			.query(Long.class)
			.list();
	}

	/** Accounts made since then, not banned, with when they were made. */
	Map<Long, Instant> newAccounts(Instant since) {
		return jdbc.sql("SELECT id, created_at FROM app_user WHERE created_at >= :since AND banned_at IS NULL")
			.param("since", Timestamp.from(since))
			.query((rs, n) -> Map.entry(rs.getLong("id"), rs.getTimestamp("created_at").toInstant()))
			.list()
			.stream()
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	/**
	 * Pairs of accounts made since then, within {@code window} of each other, with routes that share at least
	 * {@code overlap} of the longer route's scoring length.
	 */
	List<Pair> overlappingNewAccounts(Instant since, Duration window, double overlap) {
		return jdbc.sql("""
				WITH account AS (
				    SELECT id, created_at FROM app_user WHERE created_at >= :since AND banned_at IS NULL
				), route_length AS (
				    SELECT r.id AS route_id, r.user_id, sum(s.length_m) AS length_m
				    FROM route r
				    JOIN account a ON a.id = r.user_id
				    JOIN route_segment rs ON rs.route_id = r.id AND rs.scores
				    JOIN road_segment s ON s.id = rs.segment_id
				    WHERE r.removed_at IS NULL
				    GROUP BY r.id, r.user_id
				), shared AS (
				    SELECT x.route_id AS route_a, y.route_id AS route_b, sum(s.length_m) AS length_m
				    FROM route_segment x
				    JOIN route_segment y ON y.segment_id = x.segment_id AND y.route_id > x.route_id AND y.scores
				    JOIN road_segment s ON s.id = x.segment_id
				    WHERE x.scores
				      AND x.route_id IN (SELECT route_id FROM route_length)
				      AND y.route_id IN (SELECT route_id FROM route_length)
				    GROUP BY x.route_id, y.route_id
				)
				SELECT DISTINCT la.user_id AS a, lb.user_id AS b
				FROM shared sh
				JOIN route_length la ON la.route_id = sh.route_a
				JOIN route_length lb ON lb.route_id = sh.route_b
				JOIN account aa ON aa.id = la.user_id
				JOIN account ab ON ab.id = lb.user_id
				WHERE la.user_id <> lb.user_id
				  AND sh.length_m >= :overlap * greatest(la.length_m, lb.length_m)
				  AND abs(extract(epoch FROM aa.created_at - ab.created_at)) <= :window""")
			.param("since", Timestamp.from(since))
			.param("window", window.toSeconds())
			.param("overlap", overlap)
			.query(Pair.class)
			.list();
	}

	record Device(byte[] hash, List<Long> users) {
	}

	record Pair(long a, long b) {
	}

}
