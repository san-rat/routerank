package lk.routerank.auth;

import java.util.List;
import java.util.Set;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

/**
 * Checks a Google ID token from Google Identity Services: signature, issuer, audience and expiry (in the
 * decoder), then the nonce this API issued and {@code email_verified}.
 */
class GoogleIdTokens {

	static final String JWK_SET_URI = "https://www.googleapis.com/oauth2/v3/certs";

	private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

	private final JwtDecoder decoder;

	GoogleIdTokens(JwtDecoder decoder) {
		this.decoder = decoder;
	}

	/** Validators for a decoder of Google ID tokens issued to {@code clientId}. */
	static OAuth2TokenValidator<Jwt> validator(String clientId) {
		return new DelegatingOAuth2TokenValidator<>(List.of(
				new JwtTimestampValidator(),
				new JwtClaimValidator<Object>(JwtClaimNames.ISS, iss -> iss != null && ISSUERS.contains(iss.toString())),
				new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(clientId))));
	}

	/**
	 * @return the verified Google account
	 * @throws InvalidGoogleTokenException if the token fails any check
	 */
	GoogleAccount verify(String credential, String expectedNonce) {
		Jwt jwt;
		try {
			jwt = decoder.decode(credential);
		}
		catch (JwtException ex) {
			throw new InvalidGoogleTokenException("token rejected: " + ex.getMessage());
		}
		if (expectedNonce == null || !expectedNonce.equals(jwt.getClaimAsString("nonce"))) {
			throw new InvalidGoogleTokenException("nonce mismatch");
		}
		if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
			throw new InvalidGoogleTokenException("email not verified");
		}
		String email = jwt.getClaimAsString("email");
		if (jwt.getSubject() == null || email == null) {
			throw new InvalidGoogleTokenException("missing sub or email");
		}
		return new GoogleAccount(jwt.getSubject(), email);
	}

	record GoogleAccount(String sub, String email) {
	}

	static class InvalidGoogleTokenException extends RuntimeException {

		InvalidGoogleTokenException(String message) {
			super(message);
		}

	}

}
