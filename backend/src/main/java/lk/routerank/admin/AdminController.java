package lk.routerank.admin;

import lk.routerank.auth.Admins;
import lk.routerank.auth.SignedInUser;
import lk.routerank.scoring.ScoringJob;
import lk.routerank.scoring.ScoringJob.Run;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The admin API. For now only "Run now" for the scoring job; the review queue and bans come in Phase 6. */
@RestController
@RequestMapping("/api/admin")
class AdminController {

	private final Admins admins;

	private final ScoringJob scoring;

	AdminController(Admins admins, ScoringJob scoring) {
		this.admins = admins;
		this.scoring = scoring;
	}

	/** Scores and publishes now instead of at the next :00 or :30; 409 while a run is already going. */
	@PostMapping("/scoring/run")
	Run runScoring(@AuthenticationPrincipal SignedInUser user) {
		if (!admins.isAdmin(user.id())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN);
		}
		return scoring.runNow()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "a scoring run is in progress"));
	}

}
