package lk.routerank.routes;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import lk.routerank.TestcontainersConfiguration;
import lk.routerank.auth.SignedInUser;
import lk.routerank.roads.LatLon;
import lk.routerank.roads.Roads;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/** The routes API over HTTP: sign-in, validation, and the 422 body the validation screens read. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RoutesApiTests {

	static final AtomicInteger USERS = new AtomicInteger();

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	Roads roads;

	final JsonMapper json = JsonMapper.builder().build();

	RequestPostProcessor signedIn;

	@BeforeEach
	void newUser() {
		long id = jdbc.sql("""
				INSERT INTO app_user (google_sub, email, live_at)
				VALUES (:sub, :sub::text || '@example.com', now() + interval '24 hours') RETURNING id""")
			.param("sub", "api-" + USERS.incrementAndGet())
			.query(Long.class)
			.single();
		signedIn = authentication(UsernamePasswordAuthenticationToken.authenticated(new SignedInUser(id), null, List.of()));
	}

	/** As the site's proxy sends it: the shared secret, and the site's Origin on changes. */
	static MockHttpServletRequestBuilder proxied(MockHttpServletRequestBuilder request) {
		return request.header("X-RouteRank-Proxy-Secret", "test-proxy-secret")
			.header("Origin", "https://routerank.pages.dev");
	}

	String body(LatLon start, LatLon end, List<LatLon> waypoints, Integer slot) {
		return json.writeValueAsString(new RouteInput(start, end, waypoints, slot, null));
	}

	LatLon snapped(double lat, double lon) {
		return roads.snap(new LatLon(lat, lon)).snapped();
	}

	@Test
	void routesNeedSignIn() throws Exception {
		mvc.perform(proxied(get("/api/routes"))).andExpect(status().isUnauthorized());
		mvc.perform(proxied(post("/api/routes/preview")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content(body(new LatLon(6.93, 79.85), new LatLon(6.91, 79.87), List.of(), null)))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void changesNeedTheCsrfToken() throws Exception {
		mvc.perform(proxied(post("/api/routes")).with(signedIn).contentType(MediaType.APPLICATION_JSON)
			.content(body(new LatLon(6.93, 79.85), new LatLon(6.91, 79.87), List.of(), 1)))
			.andExpect(status().isForbidden());
	}

	@Test
	void previewsARoute() throws Exception {
		mvc.perform(proxied(post("/api/routes/preview")).with(signedIn).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body(snapped(6.9147, 79.8488), snapped(6.8890, 79.8553), List.of(), null)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Kollupitiya → Milagiriya"))
			.andExpect(jsonPath("$.out[0].length()").value(2))
			.andExpect(jsonPath("$.backLeaves").isNotEmpty())
			.andExpect(jsonPath("$.problems").isEmpty());
	}

	@Test
	void atMostEightWaypoints() throws Exception {
		LatLon p = new LatLon(6.92, 79.86);
		mvc.perform(proxied(post("/api/routes/preview")).with(signedIn).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body(p, p, List.of(p, p, p, p, p, p, p, p, p), null)))
			.andExpect(status().isBadRequest());
	}

	@Test
	void pointsOutsideSriLankaAreRejected() throws Exception {
		mvc.perform(proxied(post("/api/routes/preview")).with(signedIn).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body(new LatLon(51.5, -0.12), new LatLon(6.91, 79.87), List.of(), null)))
			.andExpect(status().isBadRequest());
	}

	@Test
	void aRefusedSaveListsTheProblems() throws Exception {
		mvc.perform(proxied(post("/api/routes")).with(signedIn).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content(body(new LatLon(6.9355, 79.8500), snapped(6.9147, 79.8775), List.of(), 1)))
			.andExpect(status().is(422))
			.andExpect(jsonPath("$.problems[0].code").value("SIDE_ROAD"))
			.andExpect(jsonPath("$.problems[0].point").value("start"))
			.andExpect(jsonPath("$.problems[0].nearest.lat").isNumber())
			.andExpect(jsonPath("$.problems[0].until").doesNotExist());
	}

	@Test
	void savesListsAndRemovesARoute() throws Exception {
		String created = mvc.perform(proxied(post("/api/routes")).with(signedIn).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body(snapped(6.9147, 79.8488), snapped(6.8890, 79.8553), List.of(), 2)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.slot").value(2))
			.andReturn().getResponse().getContentAsString();
		long id = json.readTree(created).get("id").asLong();

		mvc.perform(proxied(get("/api/routes")).with(signedIn))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.routes[0].id").value(id))
			.andExpect(jsonPath("$.slots.length()").value(3))
			.andExpect(jsonPath("$.slots[1].lockedUntil").isString())
			.andExpect(jsonPath("$.slots[0].lockedUntil").doesNotExist())
			.andExpect(jsonPath("$.countsFrom").isString());

		mvc.perform(proxied(delete("/api/routes/" + id)).with(signedIn).with(csrf())).andExpect(status().isNoContent());
		mvc.perform(proxied(delete("/api/routes/" + id)).with(signedIn).with(csrf())).andExpect(status().isNotFound());
	}

	@Test
	void searchesPlaces() throws Exception {
		mvc.perform(proxied(get("/api/places").param("q", "bambal")).with(signedIn))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].name").value("Bambalapitiya"));
		mvc.perform(proxied(get("/api/places").param("q", "")).with(signedIn)).andExpect(status().isBadRequest());
	}

}
