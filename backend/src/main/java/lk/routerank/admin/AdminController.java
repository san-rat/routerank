package lk.routerank.admin;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lk.routerank.admin.AdminStore.AccountRow;
import lk.routerank.admin.AdminStore.RouteSummary;
import lk.routerank.admin.Audit.Entry;
import lk.routerank.auth.Admins;
import lk.routerank.auth.SignedInUser;
import lk.routerank.fraud.ReviewQueue;
import lk.routerank.fraud.ReviewQueue.Decision;
import lk.routerank.scoring.ScoringJob;
import lk.routerank.scoring.ScoringJob.Run;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The admin API behind {@code /admin}: the review queue, holds, votes, bans, the audit log and "Run scoring now".
 * Every request needs the admin role and a fresh sign-in ({@link Admins#require}); every change takes a written
 * reason and writes the audit log, one entry per account it touches.
 */
@RestController
@RequestMapping("/api/admin")
class AdminController {

	private final Admins admins;

	private final ScoringJob scoring;

	private final ReviewQueue queue;

	private final AdminStore store;

	private final Audit audit;

	private final TransactionTemplate tx;

	private final Clock clock;

	AdminController(Admins admins, ScoringJob scoring, ReviewQueue queue, AdminStore store, Audit audit,
			TransactionTemplate tx, Clock clock) {
		this.admins = admins;
		this.scoring = scoring;
		this.queue = queue;
		this.store = store;
		this.audit = audit;
		this.tx = tx;
		this.clock = clock;
	}

	/** Whether this session may use the admin pages now (401 with code fresh_sign_in when it must sign in again). */
	@GetMapping("/session")
	Session session(@AuthenticationPrincipal SignedInUser user, HttpSession session) {
		return new Session(admins.require(user, session));
	}

	/** Scores and publishes now instead of at the next :00 or :30; 409 while a run is already going. */
	@PostMapping("/scoring/run")
	Run runScoring(@AuthenticationPrincipal SignedInUser user, HttpSession session, @Valid @RequestBody Reason reason) {
		long admin = admins.require(user, session);
		Run run = scoring.runNow()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "a scoring run is in progress"));
		audit.record(admin, "scoring.run", "scoring", null, Map.of("published", run.published()), reason.reason(),
				clock.instant());
		return run;
	}

	/** Open clusters of flagged accounts, the most suspicious first, with each account's votes. */
	@GetMapping("/queue")
	List<ClusterView> queue(@AuthenticationPrincipal SignedInUser user, HttpSession session) {
		admins.require(user, session);
		List<ReviewQueue.Cluster> clusters = queue.clusters();
		Map<Long, List<RouteSummary>> routes = routesByUser(clusters.stream()
			.flatMap(c -> c.accounts().stream())
			.map(ReviewQueue.Account::id)
			.collect(Collectors.toSet()));
		return clusters.stream()
			.map(c -> new ClusterView(c.key(), c.reason(), c.flaggedAt(), c.score(), c.accounts()
				.stream()
				.map(a -> AccountView.of(a, routes.getOrDefault(a.id(), List.of())))
				.toList()))
			.toList();
	}

	/** Every account on hold, with its votes. */
	@GetMapping("/held")
	List<AccountView> held(@AuthenticationPrincipal SignedInUser user, HttpSession session) {
		admins.require(user, session);
		List<ReviewQueue.Account> held = queue.held();
		Map<Long, List<RouteSummary>> routes = routesByUser(held.stream().map(ReviewQueue.Account::id)
			.collect(Collectors.toSet()));
		return held.stream().map(a -> AccountView.of(a, routes.getOrDefault(a.id(), List.of()))).toList();
	}

	/** Releases a whole cluster with one reason: its accounts' votes count again (unless held for another reason). */
	@PostMapping("/clusters/release")
	Resolved releaseCluster(@AuthenticationPrincipal SignedInUser user, HttpSession session,
			@Valid @RequestBody ClusterAction action) {
		long admin = admins.require(user, session);
		Instant now = clock.instant();
		return tx.execute(status -> {
			List<Long> users = queue.resolveCluster(action.cluster(), Decision.RELEASE, admin, now);
			for (long id : users) {
				audit.record(admin, "account.release", "account:" + id, Map.of("held", true),
						Map.of("held", store.account(id).map(a -> a.heldAt() != null).orElse(false), "cluster",
								action.cluster()),
						action.reason(), now);
			}
			return new Resolved(users, List.of());
		});
	}

	/** Removes a whole cluster's votes with one reason; the accounts stay on hold. */
	@PostMapping("/clusters/remove")
	Resolved removeCluster(@AuthenticationPrincipal SignedInUser user, HttpSession session,
			@Valid @RequestBody ClusterAction action) {
		long admin = admins.require(user, session);
		Instant now = clock.instant();
		return tx.execute(status -> {
			List<Long> users = queue.resolveCluster(action.cluster(), Decision.REMOVE, admin, now);
			List<Long> removed = new ArrayList<>();
			for (long id : users) {
				List<Long> routes = store.removeRoutes(id, now);
				removed.addAll(routes);
				audit.record(admin, "account.remove_votes", "account:" + id, Map.of("routes", routes),
						Map.of("removed", routes, "cluster", action.cluster()), action.reason(), now);
			}
			return new Resolved(users, removed);
		});
	}

	@GetMapping("/accounts")
	List<AccountRow> findAccounts(@AuthenticationPrincipal SignedInUser user, HttpSession session,
			@RequestParam String q) {
		admins.require(user, session);
		return q.isBlank() ? List.of() : store.find(q);
	}

	@GetMapping("/accounts/{id}")
	AccountDetail account(@AuthenticationPrincipal SignedInUser user, HttpSession session, @PathVariable long id) {
		admins.require(user, session);
		AccountRow account = store.account(id).orElseThrow(AdminController::notFound);
		return new AccountDetail(account, store.routesOf(List.of(id)));
	}

	/** Lifts an account's hold and closes its open flags. */
	@PostMapping("/accounts/{id}/release")
	void releaseAccount(@AuthenticationPrincipal SignedInUser user, HttpSession session, @PathVariable long id,
			@Valid @RequestBody Reason reason) {
		long admin = admins.require(user, session);
		Instant now = clock.instant();
		tx.executeWithoutResult(status -> {
			if (!queue.release(id, admin, now)) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "that account isn't held");
			}
			audit.record(admin, "account.release", "account:" + id, Map.of("held", true), Map.of("held", false),
					reason.reason(), now);
		});
	}

	@PostMapping("/accounts/{id}/ban")
	void ban(@AuthenticationPrincipal SignedInUser user, HttpSession session, @PathVariable long id,
			@Valid @RequestBody Reason reason) {
		long admin = admins.require(user, session);
		Instant now = clock.instant();
		tx.executeWithoutResult(status -> {
			if (!store.ban(id, now)) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "already banned, an admin, or no such account");
			}
			audit.record(admin, "account.ban", "account:" + id, Map.of("banned", false), Map.of("banned", true),
					reason.reason(), now);
		});
	}

	@PostMapping("/accounts/{id}/unban")
	void unban(@AuthenticationPrincipal SignedInUser user, HttpSession session, @PathVariable long id,
			@Valid @RequestBody Reason reason) {
		long admin = admins.require(user, session);
		Instant now = clock.instant();
		tx.executeWithoutResult(status -> {
			if (!store.unban(id)) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "that account isn't banned");
			}
			audit.record(admin, "account.unban", "account:" + id, Map.of("banned", true), Map.of("banned", false),
					reason.reason(), now);
		});
	}

	/** Removes one vote; its points come off at the next scoring run. */
	@PostMapping("/routes/{id}/remove")
	void removeRoute(@AuthenticationPrincipal SignedInUser user, HttpSession session, @PathVariable long id,
			@Valid @RequestBody Reason reason) {
		long admin = admins.require(user, session);
		Instant now = clock.instant();
		tx.executeWithoutResult(status -> {
			RouteSummary route = store.route(id).orElseThrow(AdminController::notFound);
			if (!store.removeRoute(id, now)) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "already removed");
			}
			audit.record(admin, "route.remove", "route:" + id, Map.of("removed", false, "account", route.userId()),
					Map.of("removed", true), reason.reason(), now);
		});
	}

	/** Puts a removed vote back, unless its owner has since filled that slot (409). */
	@PostMapping("/routes/{id}/restore")
	void restoreRoute(@AuthenticationPrincipal SignedInUser user, HttpSession session, @PathVariable long id,
			@Valid @RequestBody Reason reason) {
		long admin = admins.require(user, session);
		Instant now = clock.instant();
		tx.executeWithoutResult(status -> {
			RouteSummary route = store.route(id).orElseThrow(AdminController::notFound);
			if (!store.restoreRoute(id)) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "not removed, or its slot is taken now");
			}
			audit.record(admin, "route.restore", "route:" + id, Map.of("removed", true, "account", route.userId()),
					Map.of("removed", false), reason.reason(), now);
		});
	}

	/** The audit log, newest first, 50 at a time ({@code before} = the last ID seen). */
	@GetMapping("/audit")
	List<Entry> auditLog(@AuthenticationPrincipal SignedInUser user, HttpSession session,
			@RequestParam(required = false) Long before) {
		admins.require(user, session);
		return audit.recent(50, before);
	}

	private Map<Long, List<RouteSummary>> routesByUser(Set<Long> users) {
		return store.routesOf(users).stream().collect(Collectors.groupingBy(RouteSummary::userId));
	}

	private static ResponseStatusException notFound() {
		return new ResponseStatusException(HttpStatus.NOT_FOUND);
	}

	record Session(long adminId) {
	}

	record Reason(@NotBlank @Size(max = 500) String reason) {
	}

	/** @param cluster e.g. {@code burst:42} */
	record ClusterAction(@NotBlank @Size(max = 100) String cluster, @NotBlank @Size(max = 500) String reason) {
	}

	/**
	 * @param users the accounts in the cluster
	 * @param removedRoutes the votes removed (when removing)
	 */
	record Resolved(List<Long> users, List<Long> removedRoutes) {
	}

	/** @param score the cluster's highest trust score, which sorts the queue */
	record ClusterView(String key, String reason, Instant flaggedAt, double score, List<AccountView> accounts) {
	}

	/** @param reasons its open flags' triggers */
	record AccountView(long id, String email, Instant createdAt, Instant liveAt, Instant heldAt, Instant bannedAt,
			double trustScore, List<String> reasons, List<RouteSummary> routes) {

		static AccountView of(ReviewQueue.Account a, List<RouteSummary> routes) {
			return new AccountView(a.id(), a.email(), a.createdAt(), a.liveAt(), a.heldAt(), a.bannedAt(), a.trustScore(),
					a.reasons(), routes);
		}

	}

	record AccountDetail(AccountRow account, List<RouteSummary> routes) {
	}

}
