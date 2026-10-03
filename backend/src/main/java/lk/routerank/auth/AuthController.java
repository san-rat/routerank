package lk.routerank.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lk.routerank.auth.Accounts.Account;
import lk.routerank.auth.GoogleIdTokens.GoogleAccount;
import lk.routerank.auth.GoogleIdTokens.InvalidGoogleTokenException;
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
 * posts the ID token it gets back. Sign-out is {@code POST /api/auth/logout} (Spring Security's logout).
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

	static final String NONCE = AuthController.class.getName() + ".nonce";

	private static final SecureRandom RANDOM = new SecureRandom();

	private final GoogleIdTokens googleIdTokens;

	private final Accounts accounts;

	private final SecurityContextRepository contexts;

	AuthController(GoogleIdTokens googleIdTokens, Accounts accounts, SecurityContextRepository contexts) {
		this.googleIdTokens = googleIdTokens;
		this.accounts = accounts;
		this.contexts = contexts;
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
		GoogleAccount google = googleIdTokens.verify(body.credential(), expected);
		Account account = accounts.signIn(google.sub(), google.email());
		if (account.banned()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "account banned");
		}

		request.changeSessionId(); // no session fixation
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
				new SignedInUser(account.id()), null, List.of()));
		SecurityContextHolder.setContext(context);
		contexts.saveContext(context, request, response);
		return Me.of(account);
	}

	@ExceptionHandler(InvalidGoogleTokenException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	void invalidToken() {
	}

	record Nonce(String nonce) {
	}

	record GoogleSignIn(@NotBlank String credential) {
	}

}
