package lk.routerank.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import lk.routerank.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The sign-in flow over real HTTP, as the site's proxy and browser drive it. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, TestGoogle.class })
class SignInTests {

	static final String SITE = "https://routerank.pages.dev";

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	final HttpClient http = HttpClient.newHttpClient();

	final JsonMapper json = JsonMapper.builder().build();

	Browser browser;

	@BeforeEach
	void newBrowser() {
		browser = new Browser();
	}

	@Test
	void signsInAndShowsTheAccount() throws Exception {
		String nonce = browser.nonce();
		HttpResponse<String> signIn = browser.signIn(TestGoogle.idToken("alice", nonce));
		assertThat(signIn.statusCode()).isEqualTo(200);

		String sessionCookie = signIn.headers().allValues("Set-Cookie").stream()
			.filter(c -> c.startsWith("__Host-session="))
			.findFirst()
			.orElseThrow();
		assertThat(sessionCookie).contains("Path=/", "Secure", "HttpOnly", "SameSite=Lax").doesNotContain("Domain");

		HttpResponse<String> me = browser.get("/api/me");
		assertThat(me.statusCode()).isEqualTo(200);
		JsonNode account = json.readTree(me.body());
		assertThat(account.get("email").asString()).isEqualTo("alice@example.com");
		Instant createdAt = Instant.parse(account.get("createdAt").asString());
		Instant liveAt = Instant.parse(account.get("liveAt").asString());
		assertThat(Duration.between(createdAt, liveAt)).isEqualTo(Duration.ofHours(24));
	}

	@Test
	void signingInAgainKeepsTheSameAccount() throws Exception {
		long first = json.readTree(browser.signIn(TestGoogle.idToken("bob", browser.nonce())).body()).get("id").asLong();
		Browser other = new Browser();
		long second = json.readTree(other.signIn(TestGoogle.idToken("bob", other.nonce())).body()).get("id").asLong();
		assertThat(second).isEqualTo(first);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE google_sub = 'bob'", Integer.class))
			.isEqualTo(1);
	}

	@Test
	void sessionIdChangesOnSignIn() throws Exception {
		browser.nonce();
		String before = browser.cookies.get("__Host-session");
		browser.signIn(TestGoogle.idToken("carol", browser.lastNonce));
		assertThat(browser.cookies.get("__Host-session")).isNotNull().isNotEqualTo(before);
	}

	@Test
	void rejectsBadTokens() throws Exception {
		assertRejected(claims -> claims.claim("nonce", "someone-elses-nonce"));
		assertRejected(claims -> claims.claim("email_verified", false));
		assertRejected(claims -> claims.audience(List.of("another-client.apps.googleusercontent.com")));
		assertRejected(claims -> claims.issuer("https://evil.example.com"));
		assertRejected(claims -> claims.issuedAt(Instant.now().minusSeconds(7200))
			.expiresAt(Instant.now().minusSeconds(3600)));
	}

	@Test
	void aNonceWorksOnlyOnce() throws Exception {
		String nonce = browser.nonce();
		assertThat(browser.signIn(TestGoogle.idToken("dave", nonce)).statusCode()).isEqualTo(200);
		assertThat(browser.signIn(TestGoogle.idToken("dave", nonce)).statusCode()).isEqualTo(401);
	}

	@Test
	void bannedAccountsCannotSignIn() throws Exception {
		browser.signIn(TestGoogle.idToken("erin", browser.nonce()));
		jdbc.update("UPDATE app_user SET banned_at = now() WHERE google_sub = 'erin'");
		assertThat(browser.get("/api/me").statusCode()).isEqualTo(401);
		Browser again = new Browser();
		assertThat(again.signIn(TestGoogle.idToken("erin", again.nonce())).statusCode()).isEqualTo(403);
	}

	@Test
	void signedOutMeIs401() throws Exception {
		assertThat(browser.get("/api/me").statusCode()).isEqualTo(401);
	}

	@Test
	void signOutEndsTheSession() throws Exception {
		browser.signIn(TestGoogle.idToken("frank", browser.nonce()));
		assertThat(browser.post("/api/auth/logout", "", SITE, true).statusCode()).isEqualTo(204);
		assertThat(browser.get("/api/me").statusCode()).isEqualTo(401);
	}

	@Test
	void changesNeedTheSitesOriginAndTheCsrfToken() throws Exception {
		String token = TestGoogle.idToken("grace", browser.nonce());
		String body = signInBody(token);
		assertThat(browser.post("/api/auth/google", body, null, true).statusCode()).isEqualTo(403);
		assertThat(browser.post("/api/auth/google", body, "https://evil.example.com", true).statusCode())
			.isEqualTo(403);
		assertThat(browser.post("/api/auth/google", body, SITE, false).statusCode()).isEqualTo(403);
		assertThat(browser.post("/api/auth/google", body, SITE, true).statusCode()).isEqualTo(200);
	}

	@Test
	void everythingButHealthNeedsTheProxySecret() throws Exception {
		assertThat(send(HttpRequest.newBuilder(uri("/api/me")).GET(), null).statusCode()).isEqualTo(403);
		assertThat(send(HttpRequest.newBuilder(uri("/api/auth/nonce")).GET(), "wrong").statusCode()).isEqualTo(403);
		assertThat(send(HttpRequest.newBuilder(uri("/actuator/health")).GET(), null).statusCode()).isEqualTo(200);
	}

	private void assertRejected(Consumer<JwtClaimsSet.Builder> change) throws Exception {
		Browser fresh = new Browser();
		String token = TestGoogle.idToken("mallory", fresh.nonce(), change);
		assertThat(fresh.signIn(token).statusCode()).isEqualTo(401);
	}

	private String signInBody(String token) {
		return json.writeValueAsString(Map.of("credential", token, "turnstile", "pass"));
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private HttpResponse<String> send(HttpRequest.Builder request, String proxySecret)
			throws IOException, InterruptedException {
		if (proxySecret != null) {
			request.header(ProxySecretFilter.HEADER, proxySecret);
		}
		return http.send(request.build(), BodyHandlers.ofString());
	}

	/** Keeps cookies like a browser and sends what the site's proxy adds. */
	class Browser {

		final Map<String, String> cookies = new LinkedHashMap<>();

		String lastNonce;

		String nonce() throws Exception {
			HttpResponse<String> response = get("/api/auth/nonce");
			assertThat(response.statusCode()).isEqualTo(200);
			lastNonce = json.readTree(response.body()).get("nonce").asString();
			return lastNonce;
		}

		HttpResponse<String> signIn(String idToken) throws Exception {
			return post("/api/auth/google", signInBody(idToken), SITE, true);
		}

		HttpResponse<String> get(String path) throws Exception {
			return exchange(HttpRequest.newBuilder(uri(path)).GET());
		}

		HttpResponse<String> post(String path, String body, String origin, boolean withCsrf) throws Exception {
			HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
				.header("Content-Type", "application/json")
				.POST(BodyPublishers.ofString(body));
			if (origin != null) {
				request.header("Origin", origin);
			}
			if (withCsrf && cookies.containsKey("XSRF-TOKEN")) {
				request.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
			}
			return exchange(request);
		}

		private HttpResponse<String> exchange(HttpRequest.Builder request) throws Exception {
			if (!cookies.isEmpty()) {
				request.header("Cookie", cookies.entrySet().stream()
					.map(c -> c.getKey() + "=" + c.getValue())
					.collect(Collectors.joining("; ")));
			}
			HttpResponse<String> response = send(request, "test-proxy-secret");
			for (String setCookie : response.headers().allValues("Set-Cookie")) {
				String pair = setCookie.split(";", 2)[0];
				String name = pair.substring(0, pair.indexOf('='));
				String value = pair.substring(pair.indexOf('=') + 1);
				if (value.isEmpty() || setCookie.contains("Max-Age=0")) {
					cookies.remove(name);
				}
				else {
					cookies.put(name, value);
				}
			}
			return response;
		}

	}

}
