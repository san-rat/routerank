package lk.routerank;

import java.util.List;

import lk.routerank.fraud.BotCheckException;
import lk.routerank.fraud.TurnstileVerifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	// Same image as infra/docker-compose.yml
	static final DockerImageName POSTGIS = DockerImageName.parse("postgis/postgis:18-3.6")
		.asCompatibleSubstituteFor("postgres");

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(POSTGIS);
	}

	/**
	 * Stands in for Cloudflare: "pass" passes for any action, "pass-&lt;action&gt;" only for that action, "down" is
	 * Cloudflare unreachable, and anything else fails.
	 */
	@Bean
	@Primary
	TurnstileVerifier testTurnstile() {
		return (secret, token, remoteIp) -> {
			if (token.equals("down")) {
				throw new BotCheckException.Unavailable("test: Cloudflare down");
			}
			boolean pass = token.equals("pass") || token.startsWith("pass-");
			String action = token.startsWith("pass-") ? token.substring(5) : null;
			return new TurnstileVerifier.Result(pass, action, "routerank.pages.dev",
					pass ? List.of() : List.of("invalid-input-response"));
		};
	}

}
