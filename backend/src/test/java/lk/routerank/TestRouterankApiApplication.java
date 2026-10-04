package lk.routerank;

import lk.routerank.auth.DevSignIn;
import org.springframework.boot.SpringApplication;

/**
 * Runs the API locally with a stand-in for Google sign-in ({@link DevSignIn}), against the database in the
 * environment (e.g. the Compose one): {@code ./gradlew bootTestRun}. See backend/README or the frontend README.
 */
public class TestRouterankApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(RouterankApiApplication::main).with(DevSignIn.class).run(args);
	}

}
