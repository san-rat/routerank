package lk.routerank.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who may use the admin API: accounts with the admin role, which is set by hand in the database (a trigger audits
 * every change). Admin pages also need a fresh sign-in: entering them needs a Google sign-in from the last 15
 * minutes, and admin access then lasts while it is used, ending after 30 minutes idle.
 */
@Service
public class Admins {

	static final Duration FRESH_SIGN_IN = Duration.ofMinutes(15);

	static final Duration IDLE = Duration.ofMinutes(30);

	/** When this session last signed in with Google (set by {@link AuthController}). */
	public static final String SIGNED_IN_AT = Admins.class.getName() + ".signedInAt";

	/** When this session last used the admin API. */
	static final String ADMIN_SEEN_AT = Admins.class.getName() + ".adminSeenAt";

	private final Accounts accounts;

	private final Clock clock;

	Admins(Accounts accounts, Clock clock) {
		this.accounts = accounts;
		this.clock = clock;
	}

	public boolean isAdmin(long userId) {
		return accounts.isAdmin(userId);
	}

	/**
	 * Checks an admin API request.
	 *
	 * @return the admin's account ID
	 * @throws ResponseStatusException 403 when the account isn't an admin
	 * @throws FreshSignInRequiredException when the admin must sign in with Google again (401)
	 */
	public long require(SignedInUser user, HttpSession session) {
		if (!accounts.isAdmin(user.id())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN);
		}
		Instant now = clock.instant();
		Instant seen = (Instant) session.getAttribute(ADMIN_SEEN_AT);
		Instant signedIn = (Instant) session.getAttribute(SIGNED_IN_AT);
		boolean active = seen != null && !seen.plus(IDLE).isBefore(now);
		boolean fresh = signedIn != null && !signedIn.plus(FRESH_SIGN_IN).isBefore(now);
		if (!active && !fresh) {
			session.removeAttribute(ADMIN_SEEN_AT);
			throw new FreshSignInRequiredException();
		}
		session.setAttribute(ADMIN_SEEN_AT, now);
		return user.id();
	}

	/** Admin pages need a Google sign-in from the last 15 minutes; the site shows the sign-in button again. */
	public static class FreshSignInRequiredException extends RuntimeException {

		FreshSignInRequiredException() {
			super("sign in again to use the admin pages");
		}

	}

}
