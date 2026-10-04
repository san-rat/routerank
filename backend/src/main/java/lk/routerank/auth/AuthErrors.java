package lk.routerank.auth;

import java.util.Map;

import lk.routerank.auth.Admins.FreshSignInRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Tells the admin pages to show the Google sign-in button again, from whichever module's admin API refused. */
@RestControllerAdvice
class AuthErrors {

	@ExceptionHandler(FreshSignInRequiredException.class)
	ResponseEntity<Map<String, String>> freshSignIn() {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code", "fresh_sign_in"));
	}

}
