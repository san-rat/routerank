package lk.routerank.auth;

import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code app_user} table. */
@Repository
class Accounts {

	private final JdbcClient jdbc;

	Accounts(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Finds the account for a Google user, creating it on first sign-in with live_at = now + 24 hours. */
	Account signIn(String googleSub, String email) {
		return jdbc.sql("""
				INSERT INTO app_user (google_sub, email, live_at)
				VALUES (:sub, :email, now() + interval '24 hours')
				ON CONFLICT (google_sub) DO UPDATE SET email = EXCLUDED.email
				RETURNING id, email, created_at, live_at, banned_at""")
			.param("sub", googleSub)
			.param("email", email)
			.query(Account.class)
			.single();
	}

	Optional<Account> find(long id) {
		return jdbc.sql("SELECT id, email, created_at, live_at, banned_at FROM app_user WHERE id = :id")
			.param("id", id)
			.query(Account.class)
			.optional();
	}

	record Account(long id, String email, Instant createdAt, Instant liveAt, Instant bannedAt) {

		boolean banned() {
			return bannedAt != null;
		}

	}

}
