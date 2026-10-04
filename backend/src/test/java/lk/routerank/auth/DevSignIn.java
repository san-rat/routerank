package lk.routerank.auth;

import static org.springframework.web.servlet.function.RequestPredicates.GET;
import static org.springframework.web.servlet.function.RouterFunctions.route;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Local development only (test sources, never in the built app): stands in for Google so you can sign in without a
 * Google account. {@code GET /api/auth/dev-token?sub=dev&nonce=...} returns an ID token signed with
 * {@link TestGoogle}'s local key, which the replaced decoder accepts; post it to {@code /api/auth/google} as usual.
 * Used by {@link lk.routerank.TestRouterankApiApplication}.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(TestGoogle.class)
public class DevSignIn {

	@Bean
	RouterFunction<ServerResponse> devToken() {
		return route(GET("/api/auth/dev-token"), request -> ServerResponse.ok()
			.body(TestGoogle.idToken(request.param("sub").orElse("dev"), request.param("nonce").orElseThrow())));
	}

}
