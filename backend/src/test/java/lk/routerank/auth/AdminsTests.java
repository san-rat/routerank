package lk.routerank.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import lk.routerank.TestClock;
import lk.routerank.auth.Admins.FreshSignInRequiredException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

/** Admin pages need a Google sign-in from the last 15 minutes, then last while used, ending after 30 idle. */
class AdminsTests {

	final TestClock clock = new TestClock();

	final Admins admins = new Admins(new Accounts(null) {
		@Override
		boolean isAdmin(long id) {
			return id == 1;
		}
	}, clock);

	final SignedInUser admin = new SignedInUser(1);

	final MockHttpSession session = new MockHttpSession();

	void signIn() {
		session.setAttribute(Admins.SIGNED_IN_AT, clock.instant());
	}

	@Test
	void votersAreRefused() {
		signIn();
		assertThatThrownBy(() -> admins.require(new SignedInUser(2), session))
			.isInstanceOf(ResponseStatusException.class)
			.hasMessageContaining("403");
	}

	@Test
	void enteringNeedsASignInFromTheLast15Minutes() {
		assertThatThrownBy(() -> admins.require(admin, session)).isInstanceOf(FreshSignInRequiredException.class);
		signIn();
		clock.advance(Duration.ofMinutes(16));
		assertThatThrownBy(() -> admins.require(admin, session)).isInstanceOf(FreshSignInRequiredException.class);
		signIn();
		assertThatCode(() -> admins.require(admin, session)).doesNotThrowAnyException();
	}

	@Test
	void accessLastsWhileUsedAndEndsAfter30MinutesIdle() {
		signIn();
		admins.require(admin, session);
		for (int i = 0; i < 4; i++) {
			clock.advance(Duration.ofMinutes(25)); // well past the 15-minute sign-in, but in use
			assertThatCode(() -> admins.require(admin, session)).doesNotThrowAnyException();
		}
		clock.advance(Duration.ofMinutes(31));
		assertThatThrownBy(() -> admins.require(admin, session)).isInstanceOf(FreshSignInRequiredException.class);
	}

}
