package lk.routerank.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Signs in through the real {@code /api/auth/google} flow in MockMvc tests (with {@link TestGoogle} imported), so the
 * session is a real one in PostgreSQL, as the admin pages' fresh sign-in check needs.
 */
public final class MockMvcSignIn {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private MockMvcSignIn() {
	}

	/**
	 * @param device the device signal's visitorId to send, or null
	 * @return the session cookie to send with later requests
	 */
	public static Cookie signIn(MockMvc mvc, String sub, String device) throws Exception {
		MvcResult nonce = mvc.perform(proxied(get("/api/auth/nonce"))).andReturn();
		Cookie session = sessionCookie(nonce);
		String value = JSON.readTree(nonce.getResponse().getContentAsString()).get("nonce").asString();
		Map<String, Object> body = new HashMap<>();
		body.put("credential", TestGoogle.idToken(sub, value));
		body.put("turnstile", "pass");
		body.put("device", device);
		MvcResult signedIn = mvc.perform(proxied(post("/api/auth/google")).cookie(session).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(JSON.writeValueAsString(body))).andReturn();
		if (signedIn.getResponse().getStatus() != 200) {
			throw new IllegalStateException("sign-in failed: " + signedIn.getResponse().getStatus() + " "
					+ signedIn.getResponse().getContentAsString());
		}
		return sessionCookie(signedIn);
	}

	/** The session cookie: "SESSION" under MockMvc (the server's cookie name setting doesn't apply there). */
	private static Cookie sessionCookie(MvcResult result) {
		Cookie cookie = result.getResponse().getCookie("__Host-session");
		return cookie != null ? cookie : result.getResponse().getCookie("SESSION");
	}

	public static MockHttpServletRequestBuilder proxied(MockHttpServletRequestBuilder request) {
		return request.header("X-RouteRank-Proxy-Secret", "test-proxy-secret")
			.header("Origin", "https://routerank.pages.dev");
	}

}
