package lk.routerank.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class MeController {

	private final Accounts accounts;

	MeController(Accounts accounts) {
		this.accounts = accounts;
	}

	/** The signed-in account; 401 when signed out. */
	@GetMapping("/api/me")
	Me me(@AuthenticationPrincipal SignedInUser user) {
		return accounts.find(user.id())
			.filter(account -> !account.banned())
			.map(Me::of)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
	}

}
