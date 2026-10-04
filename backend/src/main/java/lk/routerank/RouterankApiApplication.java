package lk.routerank;

import java.time.Clock;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

// The API is always reached through the site's own /api proxy, so the spec uses a relative server
@OpenAPIDefinition(info = @Info(title = "RouteRank API", version = "v1"), servers = @Server(url = "/"))
@SpringBootApplication
@ConfigurationPropertiesScan
public class RouterankApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(RouterankApiApplication.class, args);
	}

	/** All times are UTC; tests replace this to move time forward (cooldowns). */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
