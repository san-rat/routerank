package lk.routerank.auth;

import java.time.Instant;

import lk.routerank.auth.Accounts.Account;

/**
 * The signed-in account as the site shows it.
 *
 * @param liveAt when this account's votes start counting (sign-up + 24 hours)
 */
record Me(long id, String email, Instant createdAt, Instant liveAt) {

	static Me of(Account account) {
		return new Me(account.id(), account.email(), account.createdAt(), account.liveAt());
	}

}
