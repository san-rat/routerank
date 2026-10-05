package lk.routerank.fraud;

import java.net.URI;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cloudflare Turnstile, checked on sign-in and route saves.
 *
 * @param secret the widget's secret key (environment only); without it the check is off, as in local development
 * @param verifyUrl Cloudflare's siteverify endpoint
 * @param hostnames when set, a token must come from one of these sites
 */
@ConfigurationProperties("routerank.turnstile")
record TurnstileProperties(String secret,
		@DefaultValue("https://challenges.cloudflare.com/turnstile/v0/siteverify") URI verifyUrl,
		@DefaultValue List<String> hostnames) {

	boolean enabled() {
		return secret != null && !secret.isBlank();
	}

}
