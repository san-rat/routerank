package lk.routerank.auth;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Stands in for Google: signs ID tokens with a local key, and replaces the decoder so it trusts that key
 * instead of Google's. The issuer, audience and expiry checks are the real ones.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestGoogle {

	static final String CLIENT_ID = "test-client.apps.googleusercontent.com";

	private static final KeyPair KEYS = generate();

	private static final NimbusJwtEncoder ENCODER = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(
			new RSAKey.Builder((RSAPublicKey) KEYS.getPublic()).privateKey((RSAPrivateKey) KEYS.getPrivate())
				.keyID("test")
				.build())));

	@Bean
	@Primary
	JwtDecoder testGoogleIdTokenDecoder() {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
		decoder.setJwtValidator(GoogleIdTokens.validator(CLIENT_ID));
		return decoder;
	}

	/** A valid ID token for {@code sub}, changed by {@code customize}. */
	static String idToken(String sub, String nonce, Consumer<JwtClaimsSet.Builder> customize) {
		Instant now = Instant.now();
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
			.issuer("https://accounts.google.com")
			.audience(List.of(CLIENT_ID))
			.subject(sub)
			.claim("email", sub + "@example.com")
			.claim("email_verified", true)
			.claim("nonce", nonce)
			.issuedAt(now)
			.expiresAt(now.plusSeconds(3600));
		customize.accept(claims);
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("test").build();
		return ENCODER.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
	}

	static String idToken(String sub, String nonce) {
		return idToken(sub, nonce, claims -> {
		});
	}

	private static KeyPair generate() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
