package lk.routerank.busroutes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import lk.routerank.TestcontainersConfiguration;
import lk.routerank.auth.MockMvcSignIn;
import lk.routerank.auth.TestGoogle;
import lk.routerank.auth.SignedInUser;
import lk.routerank.roads.LatLon;
import lk.routerank.roads.Roads;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import jakarta.servlet.http.Cookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bus routes on the Colombo test roads: an admin draws Fort → Kollupitiya, and voters extend it down Galle Road to
 * Bambalapitiya. Only the new part scores.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestGoogle.class })
class BusRoutesTests {

	static final AtomicInteger USERS = new AtomicInteger();

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	Roads roads;

	@Autowired
	BusRoutes busRoutes;

	final JsonMapper json = JsonMapper.builder().build();

	Cookie admin;

	RequestPostProcessor voter;

	long voterId;

	LatLon fort;

	LatLon kollupitiya;

	LatLon bambalapitiya;

	@BeforeEach
	void setUp() throws Exception {
		String sub = "bus-admin-" + USERS.incrementAndGet();
		admin = MockMvcSignIn.signIn(mvc, sub, null);
		jdbc.sql("UPDATE app_user SET role = 'admin' WHERE google_sub = :sub").param("sub", sub).update();
		voterId = user("voter");
		voter = signedIn(voterId);
		fort = snapped(6.9344, 79.8428);
		kollupitiya = snapped(6.9147, 79.8488);
		bambalapitiya = snapped(6.8890, 79.8553);
	}

	/** Bus routes stay out of the other tests sharing this database. */
	@AfterEach
	void retireAll() {
		jdbc.sql("UPDATE bus_route SET retired_at = now() WHERE retired_at IS NULL").update();
		busRoutes.reload();
	}

	long user(String kind) {
		return jdbc.sql("""
				INSERT INTO app_user (google_sub, email, created_at, live_at)
				VALUES (:sub, :sub::text || '@example.com', now() - interval '2 days', now() - interval '1 day')
				RETURNING id""")
			.param("sub", "bus-" + kind + "-" + USERS.incrementAndGet())
			.query(Long.class)
			.single();
	}

	static RequestPostProcessor signedIn(long id) {
		return authentication(UsernamePasswordAuthenticationToken.authenticated(new SignedInUser(id), null, List.of()));
	}

	static MockHttpServletRequestBuilder proxied(MockHttpServletRequestBuilder request) {
		return request.header("X-RouteRank-Proxy-Secret", "test-proxy-secret")
			.header("Origin", "https://routerank.pages.dev")
			.contentType(MediaType.APPLICATION_JSON);
	}

	LatLon snapped(double lat, double lon) {
		return roads.snap(new LatLon(lat, lon)).snapped();
	}

	ResultActions asAdmin(MockHttpServletRequestBuilder request, Object body) throws Exception {
		return mvc.perform(proxied(request).cookie(admin).with(csrf())
			.content(json.writeValueAsString(body)));
	}

	Map<String, Object> bus(String number, LatLon start, LatLon end, String reason) {
		Map<String, Object> body = new HashMap<>();
		body.put("number", number);
		body.put("start", start);
		body.put("end", end);
		body.put("waypoints", List.of());
		body.put("reason", reason);
		return body;
	}

	long addFortKollupitiya() throws Exception {
		String body = asAdmin(post("/api/admin/bus-routes"), bus("101", fort, kollupitiya, "Real route, from the NTC list"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return json.readTree(body).get("id").asLong();
	}

	Map<String, Object> route(LatLon start, LatLon end, Integer slot, Long extend) {
		Map<String, Object> body = new HashMap<>();
		body.put("start", start);
		body.put("end", end);
		body.put("waypoints", List.of());
		body.put("slot", slot);
		body.put("extend", extend);
		body.put("turnstile", "pass");
		return body;
	}

	JsonNode preview(LatLon start, LatLon end) throws Exception {
		return json.readTree(mvc.perform(proxied(post("/api/routes/preview")).with(voter).with(csrf())
			.content(json.writeValueAsString(route(start, end, null, null))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString());
	}

	@Test
	void anAdminDrawsABusRouteAndBothDirectionsComeOut() throws Exception {
		asAdmin(post("/api/admin/bus-routes/draw"), bus("101", fort, kollupitiya, null))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.out.length()").value(org.hamcrest.Matchers.greaterThan(2)))
			.andExpect(jsonPath("$.back.length()").value(org.hamcrest.Matchers.greaterThan(2)))
			.andExpect(jsonPath("$.startName").value("Fort"))
			.andExpect(jsonPath("$.endName").value("Kollupitiya"))
			.andExpect(jsonPath("$.problems").isEmpty());

		long id = addFortKollupitiya();
		asAdmin(get("/api/admin/bus-routes"), Map.of())
			.andExpect(jsonPath("$[0].id").value(id))
			.andExpect(jsonPath("$[0].number").value("101"))
			.andExpect(jsonPath("$[0].extensions").value(0));
		assertThat(jdbc.sql("SELECT count(*) FROM bus_route_segment WHERE bus_route_id = :id").param("id", id)
			.query(Integer.class).single()).isPositive();
		assertThat(jdbc.sql("""
				SELECT reason FROM audit_log WHERE action = 'bus_route.add' AND target = :target""")
			.param("target", "bus_route:" + id).query(String.class).single()).isEqualTo("Real route, from the NTC list");

		// One active route per number; a reason is required
		asAdmin(post("/api/admin/bus-routes/draw"), bus("101", fort, kollupitiya, null))
			.andExpect(jsonPath("$.problems[0]").value("NUMBER_TAKEN"));
		asAdmin(post("/api/admin/bus-routes"), bus("102", fort, kollupitiya, " ")).andExpect(status().isBadRequest());
	}

	@Test
	void theWayBackCanBeDrawnOnADifferentRoad() throws Exception {
		LatLon townHall = snapped(6.9167, 79.8636);
		Map<String, Object> fastest = bus("101", fort, kollupitiya, "Real route, from the NTC list");
		Map<String, Object> viaTownHall = new HashMap<>(fastest);
		viaTownHall.put("backWaypoints", List.of(townHall));

		JsonNode plain = json.readTree(asAdmin(post("/api/admin/bus-routes/draw"), fastest).andReturn().getResponse()
			.getContentAsString());
		JsonNode drawn = json.readTree(asAdmin(post("/api/admin/bus-routes/draw"), viaTownHall)
			.andExpect(jsonPath("$.problems").isEmpty())
			.andExpect(jsonPath("$.backWaypoints.length()").value(1))
			.andReturn().getResponse().getContentAsString());
		assertThat(drawn.get("lengthOutM").asDouble()).isEqualTo(plain.get("lengthOutM").asDouble());
		assertThat(drawn.get("lengthBackM").asDouble()).isGreaterThan(plain.get("lengthBackM").asDouble() + 500);
		assertThat(drawn.get("backVia")).isNotEmpty();
		assertThat(plain.get("backWaypoints")).isEmpty();

		// Saved and listed with its own waypoints; redrawing keeps them apart from the way there's
		long id = json.readTree(asAdmin(post("/api/admin/bus-routes"), viaTownHall).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString()).get("id").asLong();
		asAdmin(get("/api/admin/bus-routes"), Map.of())
			.andExpect(jsonPath("$[0].id").value(id))
			.andExpect(jsonPath("$[0].waypoints").isEmpty())
			.andExpect(jsonPath("$[0].backWaypoints.length()").value(1))
			.andExpect(jsonPath("$[0].lengthBackM").value(drawn.get("lengthBackM").asDouble()));
		assertThat(jdbc.sql("SELECT back_via FROM bus_route WHERE id = :id").param("id", id)
			.query((rs, n) -> List.of((String[]) rs.getArray(1).getArray())).single())
			.containsExactlyElementsOf(drawn.get("backVia").valueStream().map(JsonNode::asString).toList());

		// Back to the fastest way back
		asAdmin(put("/api/admin/bus-routes/" + id), fastest).andExpect(status().isNoContent());
		asAdmin(get("/api/admin/bus-routes"), Map.of())
			.andExpect(jsonPath("$[0].backWaypoints").isEmpty())
			.andExpect(jsonPath("$[0].lengthBackM").value(plain.get("lengthBackM").asDouble()));

		// A way-back waypoint out at sea
		Map<String, Object> atSea = new HashMap<>(fastest);
		atSea.put("backWaypoints", List.of(new LatLon(6.9, 79.7)));
		asAdmin(post("/api/admin/bus-routes/draw"), atSea)
			.andExpect(jsonPath("$.problems[0]").value("NO_ROAD_NEARBY:backWaypoint"));
	}

	@Test
	void voterPagesCantChangeBusRoutes() throws Exception {
		mvc.perform(proxied(post("/api/admin/bus-routes")).with(voter).with(csrf())
			.content(json.writeValueAsString(bus("101", fort, kollupitiya, "trying")))).andExpect(status().isForbidden());
	}

	@Test
	void aRouteFromTheBusEndOnIsOfferedAsAnExtension() throws Exception {
		long bus = addFortKollupitiya();
		JsonNode preview = preview(kollupitiya, bambalapitiya);
		JsonNode check = preview.get("bus");
		assertThat(check).as("the Bus check").isNotNull();
		assertThat(check.get("busRouteId").asLong()).isEqualTo(bus);
		assertThat(check.get("number").asString()).isEqualTo("101");
		assertThat(check.get("busName").asString()).isEqualTo("Fort → Kollupitiya");
		assertThat(check.get("name").asString()).isEqualTo("Fort → " + check.get("newEnd").asString());
		assertThat(check.get("totalM").asDouble())
			.isCloseTo(check.get("busLengthM").asDouble() + check.get("newLengthM").asDouble(),
					org.assertj.core.data.Offset.offset(1.0));
		assertThat(check.get("problems")).isEmpty();
	}

	@Test
	void anExtensionScoresOnlyItsNewPart() throws Exception {
		long bus = addFortKollupitiya();
		// Fort to Bambalapitiya runs along the bus to Kollupitiya, then on down Galle Road
		JsonNode check = preview(fort, bambalapitiya).get("bus");
		assertThat(check).as("the Bus check").isNotNull();

		String saved = mvc.perform(proxied(post("/api/routes")).with(voter).with(csrf())
			.content(json.writeValueAsString(route(fort, bambalapitiya, 1, bus))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.extendsBus.number").value("101"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		long routeId = json.readTree(saved).get("id").asLong();
		assertThat(jdbc.sql("SELECT kind FROM route WHERE id = :id").param("id", routeId).query(String.class).single())
			.isEqualTo("extension");
		assertThat(json.readTree(saved).get("name").asString()).isEqualTo(check.get("name").asString());

		// The road the bus already runs on earns nothing; the rest does
		Map<String, Object> scores = jdbc.sql("""
				SELECT count(*) FILTER (WHERE rs.scores AND bs.segment_id IS NOT NULL) AS on_bus_scoring,
				       count(*) FILTER (WHERE NOT rs.scores) AS not_scoring,
				       count(*) FILTER (WHERE rs.scores) AS scoring
				FROM route_segment rs
				LEFT JOIN bus_route_segment bs ON bs.segment_id = rs.segment_id AND bs.bus_route_id = :bus
				WHERE rs.route_id = :route""")
			.param("bus", bus)
			.param("route", routeId)
			.query()
			.singleRow();
		assertThat((Long) scores.get("on_bus_scoring")).isZero();
		assertThat((Long) scores.get("not_scoring")).isPositive();
		assertThat((Long) scores.get("scoring")).isPositive();

		// Nothing extends a bus route that's being redrawn
		asAdmin(put("/api/admin/bus-routes/" + bus), bus("101", fort, kollupitiya, "Moved the start"))
			.andExpect(status().isConflict());
	}

	@Test
	void keepingItAsANewRouteScoresTheWholeRoute() throws Exception {
		addFortKollupitiya();
		String saved = mvc.perform(proxied(post("/api/routes")).with(voter).with(csrf())
			.content(json.writeValueAsString(route(fort, bambalapitiya, 1, null))))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		long routeId = json.readTree(saved).get("id").asLong();
		assertThat(json.readTree(saved).has("extendsBus")).isFalse();
		assertThat(jdbc.sql("SELECT count(*) FROM route_segment WHERE route_id = :id AND NOT scores").param("id", routeId)
			.query(Integer.class).single()).isZero();
	}

	@Test
	void bus40KmOrMoreCantBeExtended() throws Exception {
		long bus = addFortKollupitiya();
		// As if the bus ran 38 km each way: bus + new part goes over 40 km (W18b)
		jdbc.sql("UPDATE bus_route SET length_out_m = 38000, length_back_m = 38000 WHERE id = :id").param("id", bus).update();
		busRoutes.reload();

		JsonNode check = preview(kollupitiya, bambalapitiya).get("bus");
		assertThat(check.get("problems").get(0).get("code").asString()).isEqualTo("EXTENSION_TOO_LONG");
		assertThat(check.get("problems").get(0).get("limitM").asDouble()).isEqualTo(40_000);

		mvc.perform(proxied(post("/api/routes")).with(voter).with(csrf())
			.content(json.writeValueAsString(route(kollupitiya, bambalapitiya, 1, bus))))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.problems[0].code").value("EXTENSION_TOO_LONG"));
		// ...but it can still be kept as a new route
		mvc.perform(proxied(post("/api/routes")).with(voter).with(csrf())
			.content(json.writeValueAsString(route(kollupitiya, bambalapitiya, 1, null))))
			.andExpect(status().isCreated());
	}

	@Test
	void aRouteThatDoesntExtendTheBusCantBeSavedAsOne() throws Exception {
		long bus = addFortKollupitiya();
		// Town Hall to Thummulla is nowhere near the bus
		mvc.perform(proxied(post("/api/routes")).with(voter).with(csrf())
			.content(json.writeValueAsString(route(snapped(6.9167, 79.8636), snapped(6.8960, 79.8610), 1, bus))))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.problems[0].code").value("NOT_AN_EXTENSION"));
	}

	@Test
	void aRetiredBusRouteIsNoLongerOffered() throws Exception {
		long bus = addFortKollupitiya();
		asAdmin(post("/api/admin/bus-routes/" + bus + "/retire"), Map.of("reason", "Route withdrawn"))
			.andExpect(status().isNoContent());
		assertThat(preview(kollupitiya, bambalapitiya).has("bus")).isFalse();
		asAdmin(put("/api/admin/bus-routes/" + bus), bus("101", fort, kollupitiya, "too late"))
			.andExpect(status().isConflict());
		// Its number is free again
		addFortKollupitiya();
	}

}
