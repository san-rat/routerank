package lk.routerank.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.Cookie;
import lk.routerank.TestcontainersConfiguration;
import lk.routerank.auth.MockMvcSignIn;
import lk.routerank.auth.SignedInUser;
import lk.routerank.auth.TestGoogle;
import lk.routerank.fraud.FraudScan;
import lk.routerank.roads.LatLon;
import lk.routerank.roads.Roads;
import lk.routerank.scoring.ScoringJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The anti-fraud layers and the admin pages over HTTP, on the Colombo test roads. The Phase 6 "done when": a burst
 * of fake votes is flagged and held by the scan, stays out of the rankings, and an admin releases or removes it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestGoogle.class })
class FraudAndAdminTests {

	static final AtomicInteger USERS = new AtomicInteger();

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	Roads roads;

	@Autowired
	FraudScan scan;

	@Autowired
	ScoringJob scoring;

	final JsonMapper json = JsonMapper.builder().build();

	Cookie admin;

	long adminId;

	@BeforeEach
	void signInAdmin() throws Exception {
		String sub = "fraud-admin-" + USERS.incrementAndGet();
		admin = MockMvcSignIn.signIn(mvc, sub, null);
		adminId = jdbc.sql("UPDATE app_user SET role = 'admin' WHERE google_sub = :sub RETURNING id")
			.param("sub", sub)
			.query(Long.class)
			.single();
	}

	/** An account made {@code hoursAgo} hours ago (votes count after 24). */
	long account(double hoursAgo) {
		return jdbc.sql("""
				INSERT INTO app_user (google_sub, email, created_at, live_at)
				VALUES (:sub, :sub::text || '@example.com', now() - make_interval(secs => :secs),
				        now() - make_interval(secs => :secs) + interval '24 hours')
				RETURNING id""")
			.param("sub", "fraud-voter-" + USERS.incrementAndGet())
			.param("secs", hoursAgo * 3600)
			.query(Long.class)
			.single();
	}

	static RequestPostProcessor as(long userId) {
		return authentication(UsernamePasswordAuthenticationToken.authenticated(new SignedInUser(userId), null, List.of()));
	}

	static MockHttpServletRequestBuilder proxied(MockHttpServletRequestBuilder request) {
		return MockMvcSignIn.proxied(request).contentType(MediaType.APPLICATION_JSON);
	}

	LatLon snapped(double lat, double lon) {
		return roads.snap(new LatLon(lat, lon)).snapped();
	}

	/** Town Hall → Thummulla. */
	Map<String, Object> townHallThummulla(String turnstile) {
		return trip(6.9167, 79.8636, 6.8960, 79.8610, turnstile);
	}

	Map<String, Object> trip(double lat1, double lon1, double lat2, double lon2, String turnstile) {
		Map<String, Object> body = new HashMap<>();
		body.put("start", snapped(lat1, lon1));
		body.put("end", snapped(lat2, lon2));
		body.put("waypoints", List.of());
		body.put("slot", 1);
		body.put("turnstile", turnstile);
		return body;
	}

	ResultActions save(long userId, Map<String, Object> body) throws Exception {
		return mvc.perform(proxied(post("/api/routes")).with(as(userId)).with(csrf())
			.content(json.writeValueAsString(body)));
	}

	ResultActions asAdmin(MockHttpServletRequestBuilder request, Object body) throws Exception {
		return mvc.perform(proxied(request).cookie(admin).with(csrf()).content(json.writeValueAsString(body)));
	}

	/** The points the last scoring run gave a segment. */
	int points(long segmentId) {
		return jdbc.sql("SELECT coalesce(max(points), 0) FROM segment_score WHERE segment_id = :id")
			.param("id", segmentId)
			.query(Integer.class)
			.single();
	}

	long firstScoringSegment(long userId) {
		return jdbc.sql("""
				SELECT rs.segment_id FROM route r JOIN route_segment rs ON rs.route_id = r.id AND rs.scores
				WHERE r.user_id = :user AND r.removed_at IS NULL ORDER BY rs.segment_id LIMIT 1""")
			.param("user", userId)
			.query(Long.class)
			.single();
	}

	boolean held(long userId) {
		return jdbc.sql("SELECT held_at IS NOT NULL FROM app_user WHERE id = :id").param("id", userId)
			.query(Boolean.class).single();
	}

	/**
	 * Five accounts made within an hour, a day ago, all voting for the same road: a WhatsApp wave or a faker. Each
	 * test's burst is a different trip, so they don't join up.
	 */
	List<Long> burst(Map<String, Object> trip) throws Exception {
		List<Long> accounts = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			long id = account(25 - i * 0.2); // 12 minutes apart
			save(id, trip).andExpect(status().isCreated());
			accounts.add(id);
		}
		return accounts;
	}

	@Test
	void aBurstOfNewAccountsIsFlaggedHeldAndReleasedByAnAdmin() throws Exception {
		List<Long> burst = burst(trip(6.8960, 79.8610, 6.8850, 79.8680, "pass")); // Thummulla → Havelock
		long segment = firstScoringSegment(burst.getFirst());
		scoring.runNow().orElseThrow();
		int counted = points(segment);
		assertThat(counted).isGreaterThanOrEqualTo(5 * 3);

		FraudScan.Result result = scan.run();
		assertThat(result.burst()).isEqualTo(5);
		assertThat(burst).allMatch(this::held);
		String cluster = "burst:" + burst.getFirst();
		assertThat(jdbc.sql("SELECT count(*) FROM fraud_flag WHERE cluster_key = :c AND status = 'open'")
			.param("c", cluster).query(Integer.class).single()).isEqualTo(5);

		// Held votes look saved to their voters but stay out of the rankings
		scoring.runNow().orElseThrow();
		assertThat(points(segment)).isEqualTo(counted - 5 * 3);
		mvc.perform(proxied(get("/api/routes")).with(as(burst.getFirst())))
			.andExpect(jsonPath("$.routes.length()").value(1));

		// The review queue shows the cluster with each account's vote
		JsonNode queue = json.readTree(asAdmin(get("/api/admin/queue"), Map.of())
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		JsonNode shown = null;
		for (JsonNode c : queue) {
			if (c.get("key").asString().equals(cluster)) {
				shown = c;
			}
		}
		assertThat(shown).isNotNull();
		assertThat(shown.get("reason").asString()).isEqualTo("burst");
		assertThat(shown.get("accounts")).hasSize(5);
		assertThat(shown.get("accounts").get(0).get("routes").get(0).get("name").asString()).contains("→");

		// One click, one reason: every account released, each with its own audit entry
		asAdmin(post("/api/admin/clusters/release"), Map.of("cluster", cluster, "reason", "Shared in a school group"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.users.length()").value(5));
		assertThat(burst).noneMatch(this::held);
		assertThat(jdbc.sql("""
				SELECT count(*) FROM audit_log WHERE action = 'account.release' AND actor_id = :admin
				  AND reason = 'Shared in a school group'""")
			.param("admin", adminId).query(Integer.class).single()).isEqualTo(5);
		scoring.runNow().orElseThrow();
		assertThat(points(segment)).isEqualTo(counted);

		// A released account isn't flagged again for the same trigger
		assertThat(scan.run().burst()).isZero();
		assertThat(burst).noneMatch(this::held);
	}

	@Test
	void removingABurstRemovesItsVotesAndKeepsTheAccountsHeld() throws Exception {
		List<Long> burst = burst(trip(6.9147, 79.8488, 6.8890, 79.8553, "pass")); // Kollupitiya → Bambalapitiya
		scan.run();
		String cluster = "burst:" + burst.getFirst();
		asAdmin(post("/api/admin/clusters/remove"), Map.of("cluster", cluster, "reason", "Same phone, same minute"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.removedRoutes.length()").value(5));
		assertThat(jdbc.sql("SELECT count(*) FROM route WHERE user_id = ANY(:ids::bigint[]) AND removed_at IS NULL")
			.param("ids", burst.toArray(Long[]::new)).query(Integer.class).single()).isZero();
		assertThat(burst).allMatch(this::held);
		// They stay listed as held, and a later vote of theirs is held too
		asAdmin(get("/api/admin/held"), Map.of()).andExpect(jsonPath("$[?(@.id == " + burst.getFirst() + ")]").exists());
	}

	@Test
	void fiveAccountsOnOneDeviceAreHeld() throws Exception {
		List<Long> accounts = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			String sub = "device-five-" + USERS.incrementAndGet();
			MockMvcSignIn.signIn(mvc, sub, "0f9c3d2a1b4e5f60718293a4b5c6d7e8");
			accounts.add(jdbc.sql("SELECT id FROM app_user WHERE google_sub = :sub").param("sub", sub)
				.query(Long.class).single());
			// Held only once the fifth signs in
			assertThat(accounts).allMatch(id -> held(id) == (accounts.size() == 5));
		}
		assertThat(jdbc.sql("""
				SELECT count(DISTINCT cluster_key) FROM fraud_flag WHERE reason = 'shared_device' AND user_id = ANY(:ids::bigint[])""")
			.param("ids", accounts.toArray(Long[]::new)).query(Integer.class).single()).isEqualTo(1);
		// Only a keyed hash is stored, never the visitorId
		assertThat(jdbc.sql("SELECT count(*) FROM device_signal WHERE encode(device_hash, 'hex') LIKE '%0f9c3d2a1b4e%'")
			.query(Integer.class).single()).isZero();
	}

	@Test
	void threeAccountsOnOneDeviceAreHeldOnlyWithAnotherTrigger() throws Exception {
		List<Long> accounts = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			String sub = "device-three-" + USERS.incrementAndGet();
			MockMvcSignIn.signIn(mvc, sub, "budget-phone-model-x");
			accounts.add(jdbc.sql("SELECT id FROM app_user WHERE google_sub = :sub").param("sub", sub)
				.query(Long.class).single());
		}
		scan.run();
		assertThat(accounts).noneMatch(this::held); // identical budget phones: not enough on its own

		// One of them fills in the honeypot: a bot gets a normal reply and nothing is saved
		Map<String, Object> bot = townHallThummulla("pass");
		bot.put("website", "http://spam.example.com");
		save(accounts.getFirst(), bot).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(0));
		assertThat(jdbc.sql("SELECT count(*) FROM route WHERE user_id = :id").param("id", accounts.getFirst())
			.query(Integer.class).single()).isZero();
		assertThat(held(accounts.getFirst())).isTrue();

		scan.run();
		assertThat(jdbc.sql("SELECT reason FROM fraud_flag WHERE user_id = :id ORDER BY reason")
			.param("id", accounts.getFirst()).query(String.class).list()).containsExactly("honeypot", "shared_device");
		assertThat(held(accounts.get(1))).isFalse();
		assertThat(held(accounts.get(2))).isFalse();
		// Two signals, so it sorts above a single one
		assertThat(jdbc.sql("SELECT trust_score FROM app_user WHERE id = :id").param("id", accounts.getFirst())
			.query(Double.class).single()).isEqualTo(5.0);
	}

	@Test
	void savesNeedAPassingTurnstileTokenForSaving() throws Exception {
		long voter = account(30);
		save(voter, townHallThummulla(null)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("bot_check_failed"));
		save(voter, townHallThummulla("pass-signin")).andExpect(status().isForbidden()); // a sign-in token can't save
		save(voter, townHallThummulla("down")).andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("bot_check_unavailable"));
		save(voter, townHallThummulla("pass-save")).andExpect(status().isCreated());
		// Previews need no token
		mvc.perform(proxied(post("/api/routes/preview")).with(as(voter)).with(csrf())
			.content(json.writeValueAsString(townHallThummulla(null)))).andExpect(status().isOk());
	}

	@Test
	void signInNeedsAPassingTurnstileTokenToo() throws Exception {
		var nonce = mvc.perform(MockMvcSignIn.proxied(get("/api/auth/nonce"))).andReturn();
		Cookie session = nonce.getResponse().getCookie("SESSION");
		String value = json.readTree(nonce.getResponse().getContentAsString()).get("nonce").asString();
		mvc.perform(proxied(post("/api/auth/google")).cookie(session).with(csrf())
			.content(json.writeValueAsString(Map.of("credential", "not-checked-yet"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("bot_check_failed"));
		assertThat(value).isNotBlank();
	}

	@Test
	void adminPagesAreForAdminsOnly() throws Exception {
		long voter = account(30);
		mvc.perform(proxied(get("/api/admin/queue")).with(as(voter))).andExpect(status().isForbidden());
		mvc.perform(proxied(get("/api/admin/queue"))).andExpect(status().isUnauthorized());
		// An admin role without a fresh Google sign-in in this session: sign in again
		mvc.perform(proxied(get("/api/admin/session")).with(as(adminId)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("fresh_sign_in"));
		asAdmin(get("/api/admin/session"), Map.of()).andExpect(status().isOk());
	}

	@Test
	void everyAdminActionIsAudited() throws Exception {
		long voter = account(30);
		save(voter, townHallThummulla("pass")).andExpect(status().isCreated());
		long route = jdbc.sql("SELECT id FROM route WHERE user_id = :id").param("id", voter).query(Long.class).single();

		asAdmin(post("/api/admin/routes/" + route + "/remove"), Map.of("reason", "Spam")).andExpect(status().isOk());
		asAdmin(post("/api/admin/routes/" + route + "/remove"), Map.of("reason", "Spam")).andExpect(status().isConflict());
		asAdmin(post("/api/admin/routes/" + route + "/restore"), Map.of("reason", "Mistake")).andExpect(status().isOk());
		asAdmin(post("/api/admin/accounts/" + voter + "/ban"), Map.of("reason", "Threats in the name field"))
			.andExpect(status().isOk());
		asAdmin(post("/api/admin/accounts/" + voter + "/unban"), Map.of("reason", "Appealed")).andExpect(status().isOk());
		asAdmin(post("/api/admin/accounts/" + adminId + "/ban"), Map.of("reason", "no")).andExpect(status().isConflict());
		asAdmin(post("/api/admin/accounts/" + voter + "/ban"), Map.of("reason", " ")).andExpect(status().isBadRequest());

		assertThat(jdbc.sql("""
				SELECT action FROM audit_log WHERE actor_id = :admin AND target IN (:route, :account) ORDER BY id""")
			.param("admin", adminId)
			.param("route", "route:" + route)
			.param("account", "account:" + voter)
			.query(String.class)
			.list()).containsExactly("route.remove", "route.restore", "account.ban", "account.unban");

		// Role changes made by hand in the database are audited too, and the log can't be rewritten
		jdbc.sql("UPDATE app_user SET role = 'admin' WHERE id = :id").param("id", voter).update();
		assertThat(jdbc.sql("SELECT after->>'role' FROM audit_log WHERE action = 'account.role' AND target = :t")
			.param("t", "account:" + voter).query(String.class).single()).isEqualTo("admin");
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_log").update())
			.hasMessageContaining("append-only");

		asAdmin(get("/api/admin/audit"), Map.of()).andExpect(jsonPath("$[0].action").value("account.role"));
	}

}
