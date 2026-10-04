package lk.routerank.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lk.routerank.auth.Accounts.Account;
import lk.routerank.auth.GoogleIdTokens.GoogleAccount;
import lk.routerank.auth.GoogleIdTokens.InvalidGoogleTokenException;
import lk.routerank.fraud.Devices;
import lk.routerank.fraud.Holds;
import lk.routerank.fraud.Turnstile;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Sign-in with Google Identity Services in callback mode: the page asks for a nonce, passes it to Google, and
 * posts the ID token it gets back, with a Turnstile token and the device signal. Sign-out is
 * {@code POST /api/auth/logout} (Spring Security's logout).
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

	static final String NONCE = AuthController.class.getName() + ".nonce";

	private static final SecureRandom RANDOM = new SecureRandom();

	private final GoogleIdTokens googleIdTokens;

	private final Accounts accounts;

	private final SecurityContextRepository contexts;

	private final Turnstile turnstile;

	private final Devices devices;

	private final Holds holds;

	private final Clock clock;

	AuthController(GoogleIdTokens googleIdTokens, Accounts accounts, SecurityContextRepository contexts,
			Turnstile turnstile, Devices devices, Holds holds, Clock clock) {
		this.googleIdTokens = googleIdTokens;
		this.accounts = accounts;
		this.contexts = contexts;
		this.turnstile = turnstile;
		this.devices = devices;
		this.holds = holds;
		this.clock = clock;
	}

	/** A one-time nonce for the next Google sign-in, kept in this browser's session. */
	@GetMapping("/nonce")
	Nonce nonce(HttpSession session) {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		session.setAttribute(NONCE, nonce);
		return new Nonce(nonce);
	}

	/** Signs in with the ID token from Google Identity Services, creating the account on first sign-in. */
	@PostMapping("/google")
	Me google(@Valid @RequestBody GoogleSignIn body, HttpServletRequest request, HttpServletResponse response) {
		HttpSession session = request.getSession(false);
		String expected = session == null ? null : (String) session.getAttribute(NONCE);
		if (session != null) {
			session.removeAttribute(NONCE); // one use only
		}
		turnstile.check(body.turnstile(), Turnstile.SIGN_IN, request.getRemoteAddr());
		GoogleAccount google = googleIdTokens.verify(body.credential(), expected);
		Account account = accounts.signIn(google.sub(), google.email());
		if (account.banned()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "account banned");
		}
		devices.record(account.id(), body.device()).ifPresent(holds::deviceSeen);

		request.changeSessionId(); // no session fixation
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
				new SignedInUser(account.id()), null, List.of()));
		SecurityContextHolder.setContext(context);
		contexts.saveContext(context, request, response);
		request.getSession().setAttribute(Admins.SIGNED_IN_AT, clock.instant()); // admin pages need a fresh sign-in
		return Me.of(account);
	}

	@ExceptionHandler(InvalidGoogleTokenException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	void invalidToken() {
	}

	record Nonce(String nonce) {
	}

	/**
	 * @param turnstile the Turnstile token for the "signin" action
	 * @param device FingerprintJS's visitorId; only an HMAC of it is stored
	 */
	record GoogleSignIn(@NotBlank String credential, @Size(max = 2048) String turnstile, @Size(max = 64) String device) {
	}

}
