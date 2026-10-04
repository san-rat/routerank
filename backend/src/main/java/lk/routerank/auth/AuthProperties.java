package lk.routerank.auth;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Sign-in and proxy settings. Every value is required, so the API refuses to start without them.
 *
 * @param googleClientId the OAuth client ID that Google ID tokens must be issued for ({@code aud})
 * @param proxySecret the shared secret the Pages Function (or Vite's proxy) sends on every request
 * @param allowedOrigins origins allowed to send requests that change data
 */
@Validated
@ConfigurationProperties("routerank.auth")
record AuthProperties(@NotBlank String googleClientId, @NotBlank String proxySecret,
		@NotEmpty List<String> allowedOrigins) {
}
