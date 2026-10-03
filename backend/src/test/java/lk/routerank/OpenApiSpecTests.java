package lk.routerank;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/**
 * The frontend generates its TypeScript types from the committed spec, so the spec must match the API.
 * After changing the API, regenerate it with {@code UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiSpecTests'}
 * and then {@code npm run api:types} in frontend/.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class OpenApiSpecTests {

	static final Path SPEC = Path.of("../frontend/src/api/openapi.json");

	@LocalServerPort
	int port;

	@Test
	void committedSpecMatchesTheApi() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs"))
			.header("X-RouteRank-Proxy-Secret", "test-proxy-secret")
			.build();
		String body = HttpClient.newHttpClient().send(request, BodyHandlers.ofString()).body();
		JsonMapper json = JsonMapper.builder().build();
		String live = json.writerWithDefaultPrettyPrinter().writeValueAsString(json.readTree(body))
			.replace("\r\n", "\n") + "\n";

		if (System.getenv("UPDATE_OPENAPI") != null) {
			Files.writeString(SPEC, live);
		}
		assertThat(SPEC).exists();
		assertThat(Files.readString(SPEC).replace("\r\n", "\n"))
			.as("frontend/src/api/openapi.json is out of date; see this test's Javadoc")
			.isEqualTo(live);
	}

}
