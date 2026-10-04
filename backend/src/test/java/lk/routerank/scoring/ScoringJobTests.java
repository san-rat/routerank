package lk.routerank.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import lk.routerank.TestcontainersConfiguration;
import lk.routerank.auth.SignedInUser;
import lk.routerank.roads.LatLon;
import lk.routerank.roads.Roads;
import lk.routerank.scoring.ScoringJob.Run;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The scoring job on the Colombo fixture: routes saved through the API, scored, ranked and "published" to an
 * in-memory R2.
 */
@SpringBootTest(properties = "routerank.admin.emails=admin@example.com")
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, ScoringJobTests.MemoryStore.class })
class ScoringJobTests {

	static final AtomicInteger USERS = new AtomicInteger();

	/** R2 stand-in: keys to bodies and their Cache-Control. */
	@TestConfiguration(proxyBeanMethods = false)
	static class MemoryStore implements ObjectStore {

		final Map<String, byte[]> files = new ConcurrentHashMap<>();

		final Map<String, String> cache = new ConcurrentHashMap<>();

		@Bean
		@Primary
		MemoryStore memoryStore() {
			return this;
		}

		@Override
		public void put(String key, byte[] body, String contentType, String cacheControl) {
			files.put(key, body);
			cache.put(key, cacheControl);
		}

		@Override
		public void delete(String key) {
			files.remove(key);
			cache.remove(key);
		}

	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	Roads roads;

	@Autowired
	ScoringJob job;

	@Autowired
	MemoryStore r2;

	@Autowired
	DataSource dataSource;

	final JsonMapper json = JsonMapper.builder().build();

	@BeforeEach
	void cleanSlate() {
		jdbc.sql("UPDATE route SET removed_at = now() WHERE removed_at IS NULL").update();
		jdbc.sql("DELETE FROM published_file").update();
		jdbc.sql("DELETE FROM publish_usage").update();
		jdbc.sql("DELETE FROM stretch_link").update();
		r2.files.clear();
		r2.cache.clear();
	}

	/** A voter whose votes count (past the 24-hour delay), or not yet. */
	RequestPostProcessor voter(boolean live) {
		return voter(live, "voter" + USERS.incrementAndGet() + "@example.com");
	}

	RequestPostProcessor voter(boolean live, String email) {
		long id = jdbc.sql("""
				INSERT INTO app_user (google_sub, email, created_at, live_at)
				VALUES (:sub, :email, now() - interval '2 days', now() + (CASE WHEN :live THEN -1 ELSE 1 END) * interval '1 day')
				RETURNING id""")
			.param("sub", "scoring-" + email)
			.param("email", email)
			.param("live", live)
			.query(Long.class)
			.single();
		return authentication(UsernamePasswordAuthenticationToken.authenticated(new SignedInUser(id), null, List.of()));
	}

	static MockHttpServletRequestBuilder proxied(MockHttpServletRequestBuilder request) {
		return request.header("X-RouteRank-Proxy-Secret", "test-proxy-secret")
			.header("Origin", "https://routerank.pages.dev");
	}

	LatLon snapped(double lat, double lon) {
		return roads.snap(new LatLon(lat, lon)).snapped();
	}

	/** Kollupitiya → Milagiriya down Galle Road (about 3 km), back up Duplication Road where it's one-way. */
	void vote(RequestPostProcessor voter, int slot) throws Exception {
		String body = json.writeValueAsString(Map.of("start", snapped(6.9147, 79.8488), "end", snapped(6.8890, 79.8553),
				"waypoints", List.of(), "slot", slot));
		mvc.perform(proxied(post("/api/routes")).with(voter).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content(body)).andExpect(status().isCreated());
	}

	JsonNode manifest() {
		return json.readTree(r2.files.get("manifest.json"));
	}

	JsonNode file(String key) {
		return json.readTree(r2.files.get(key));
	}

	@Test
	void votesBecomeRankedStretchesOnTheMapAndLeaderboards() throws Exception {
		RequestPostProcessor first = voter(true);
		vote(first, 1);
		vote(voter(true), 1);
		vote(voter(true), 2);
		vote(voter(false), 1); // a new account: doesn't count for 24 hours

		Run run = job.runNow().orElseThrow();
		assertThat(run.published()).isTrue();
		assertThat(run.ranked()).isPositive();

		// Galle Road scores 3 + 3 + 2 from three people
		JsonNode m = manifest();
		JsonNode top = file(m.get("overall").get("pages").get(0).asString()).get("entries").get(0);
		assertThat(top.get("rank").asInt()).isEqualTo(1);
		assertThat(top.get("points").asInt()).isEqualTo(8);
		assertThat(top.get("people").asInt()).isEqualTo(3);
		assertThat(top.get("province").asString()).isEqualTo("western");
		assertThat(top.get("name").asString()).contains(" → ");

		JsonNode western = m.get("provinces").get("western");
		JsonNode heat = file(western.get("heat").asString());
		assertThat(heat.get("features").size()).isEqualTo(western.get("stretches").asInt());
		JsonNode details = file(western.get("details").asString()).get("stretches").get(top.get("slug").asString());
		assertThat(details.get("rankProvince").asInt()).isEqualTo(1);
		assertThat(m.get("slugs").asString()).startsWith("slugs-");

		// Cache: hashed files forever, the manifest for a minute
		assertThat(r2.cache.get("manifest.json")).isEqualTo("public, max-age=60");
		assertThat(r2.cache.get(western.get("heat").asString())).contains("immutable");

		// My routes shows the busiest stretch
		mvc.perform(proxied(get("/api/routes")).with(first))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.routes[0].busiest.points").value(8))
			.andExpect(jsonPath("$.routes[0].busiest.rank").value(1));

		// Nothing changed: only the manifest is written again
		Run again = job.runNow().orElseThrow();
		assertThat(again.uploaded()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT writes FROM publish_usage").query(Integer.class).single())
			.isEqualTo(run.uploaded() + 1);
	}

	@Test
	void oldFilesAreDeletedTwoDaysAfterTheManifestStopsNamingThem() throws Exception {
		RequestPostProcessor a = voter(true);
		vote(a, 1);
		job.runNow().orElseThrow();
		String oldHeat = manifest().get("provinces").get("western").get("heat").asString();

		vote(voter(true), 1);
		job.runNow().orElseThrow();
		assertThat(manifest().get("provinces").get("western").get("heat").asString()).isNotEqualTo(oldHeat);
		assertThat(r2.files).containsKey(oldHeat); // still there for readers holding the old manifest

		jdbc.sql("UPDATE published_file SET last_referenced_at = now() - interval '3 days' WHERE key = :key")
			.param("key", oldHeat)
			.update();
		Run run = job.runNow().orElseThrow();
		assertThat(run.deleted()).isEqualTo(1);
		assertThat(r2.files).doesNotContainKey(oldHeat);
	}

	@Test
	void stopsPublishingAtTheMonthlyWriteBudget() throws Exception {
		vote(voter(true), 1);
		jdbc.sql("INSERT INTO publish_usage (month, writes) VALUES (date_trunc('month', now() AT TIME ZONE 'UTC'), 799999)")
			.update();
		Run run = job.runNow().orElseThrow();
		assertThat(run.published()).isFalse();
		assertThat(run.note()).isEqualTo("monthly write budget reached");
		assertThat(r2.files).isEmpty();
		// Scores are still saved, so My routes stays current
		assertThat(jdbc.sql("SELECT count(*) FROM stretch").query(Integer.class).single()).isPositive();
	}

	@Test
	void runsNeverOverlap() throws Exception {
		try (Connection other = dataSource.getConnection(); Statement s = other.createStatement()) {
			s.execute("SELECT pg_advisory_lock(" + ScoringJob.LOCK_KEY + ")");
			assertThat(job.runNow()).isEmpty();
			s.execute("SELECT pg_advisory_unlock(" + ScoringJob.LOCK_KEY + ")");
		}
		assertThat(job.runNow()).isPresent();
	}

	@Test
	void onlyAdminsCanRunItNow() throws Exception {
		RequestPostProcessor admin = voter(true, "Admin@Example.com"); // in routerank.admin.emails, any case
		RequestPostProcessor someone = voter(true);
		mvc.perform(proxied(post("/api/admin/scoring/run")).with(someone).with(csrf())).andExpect(status().isForbidden());
		mvc.perform(proxied(post("/api/admin/scoring/run")).with(admin).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.published").value(true));
		mvc.perform(proxied(post("/api/admin/scoring/run")).with(csrf())).andExpect(status().isUnauthorized());
	}

}
