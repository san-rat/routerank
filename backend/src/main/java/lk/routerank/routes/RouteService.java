package lk.routerank.routes;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import lk.routerank.roads.LatLon;
import lk.routerank.roads.RoutePlan;
import lk.routerank.roads.RoutePlan.SnappedPoint;
import lk.routerank.roads.Roads;
import lk.routerank.scoring.Stretches;
import lk.routerank.scoring.Stretches.Busiest;
import lk.routerank.routes.RouteStore.Account;
import lk.routerank.routes.RouteStore.NewRoute;
import lk.routerank.routes.RouteStore.StoredRoute;
import lk.routerank.routes.Slots.Lock;
import lk.routerank.routes.Slots.Placement;
import lk.routerank.routes.Views.MyRoutes;
import lk.routerank.routes.Views.Preview;
import lk.routerank.routes.Views.RouteView;
import lk.routerank.routes.Views.SlotView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Adding, editing, reordering and removing a user's three routes, with every rule checked on the server.
 *
 * <p>Saves never trust the browser's geometry: the route is worked out again from its points and every rule runs on
 * that result. Every change first locks the user's row ({@code SELECT … FOR UPDATE}), so changes from one account
 * can't interleave. Times are {@link Instant}s (UTC) from the injected clock.
 */
@Service
class RouteService {

	/** A start or end further than this from the main road it snaps to is on a side road. */
	static final double SIDE_ROAD_M = 30;

	static final double MAX_LENGTH_M = 40_000;

	/** Stands for the route being saved in slot placement before it has an ID. */
	private static final long NEW_ROUTE = -1;

	private final Roads roads;

	private final RouteStore store;

	private final TransactionTemplate tx;

	private final Clock clock;

	private final Stretches stretches;

	RouteService(Roads roads, RouteStore store, TransactionTemplate tx, Clock clock, Stretches stretches) {
		this.roads = roads;
		this.store = store;
		this.tx = tx;
		this.clock = clock;
		this.stretches = stretches;
	}

	MyRoutes list(long userId) {
		Account account = store.account(userId).filter(a -> a.bannedAt() == null).orElseThrow(RouteService::signedOut);
		return myRoutes(userId, account, clock.instant());
	}

	Preview preview(long userId, RouteInput input) {
		RoutePlan plan = roads.plan(input.points());
		List<Problem> problems = new ArrayList<>(routeProblems(plan));
		if (problems.isEmpty()) {
			store.overlaps(userId, plan.segmentIds(), input.routeId())
				.forEach(o -> problems.add(Problem.overlap(o.id(), o.slot(), o.name())));
		}
		List<LatLon> points = plan.points().stream()
			.map(p -> p.snapped() != null ? p.snapped() : p.input())
			.toList();
		var out = plan.out();
		var back = plan.back();
		return new Preview(points, out == null ? new double[0][] : Views.line(out.line()),
				back == null ? new double[0][] : Views.line(back.line()),
				back == null ? List.of() : back.leaves().stream().map(Views::line).toList(),
				back == null ? List.of() : back.leavesVia(), out == null ? 0 : out.lengthM(),
				back == null ? 0 : back.lengthM(), out == null ? "" : roads.name(points.getFirst(), points.getLast()),
				problems);
	}

	RouteView create(long userId, RouteInput input) {
		if (input.slot() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "slot is required");
		}
		RoutePlan plan = roads.plan(input.points());
		return tx.execute(status -> {
			lock(userId);
			Instant now = clock.instant();
			List<StoredRoute> routes = store.active(userId);
			List<Problem> problems = problems(userId, plan, null);
			Placement placement = place(routes, null, input.slot(), problems);
			checkLocks(userId, placement, now, problems);
			refuseIf(problems);

			moveShifted(routes, placement, now);
			long id = store.insert(userId, input.slot(), toStore(plan), now);
			startLocks(userId, placement, now);
			return view(store.active(userId), id);
		});
	}

	RouteView update(long userId, long routeId, RouteInput input) {
		RoutePlan plan = roads.plan(input.points());
		return tx.execute(status -> {
			lock(userId);
			Instant now = clock.instant();
			List<StoredRoute> routes = store.active(userId);
			StoredRoute route = routes.stream().filter(r -> r.id() == routeId).findFirst().orElseThrow(RouteService::notFound);
			int slot = input.slot() == null ? route.slot() : input.slot();
			List<Problem> problems = problems(userId, plan, routeId);
			Placement placement = place(routes, routeId, slot, problems);
			checkLocks(userId, placement, now, problems);
			refuseIf(problems);

			moveShifted(routes, placement, now);
			store.update(routeId, toStore(plan), now);
			store.moveToSlot(routeId, slot, now);
			startLocks(userId, placement, now);
			return view(store.active(userId), routeId);
		});
	}

	/** Puts the user's routes in new slots; every route must be listed, each in a different slot. */
	MyRoutes reorder(long userId, Map<Long, Integer> slots) {
		return tx.execute(status -> {
			Account account = lock(userId);
			Instant now = clock.instant();
			Map<Long, Integer> current = store.active(userId).stream()
				.collect(Collectors.toMap(StoredRoute::id, StoredRoute::slot));
			if (!current.keySet().equals(slots.keySet()) || new HashSet<>(slots.values()).size() != slots.size()) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "list each of your routes once, in different slots");
			}
			Set<Integer> touched = Slots.touchedByReorder(current, slots);
			Placement placement = new Placement(slots, touched);
			List<Problem> problems = new ArrayList<>();
			checkLocks(userId, placement, now, problems);
			refuseIf(problems);

			slots.forEach((id, slot) -> {
				if (!slot.equals(current.get(id))) {
					store.moveToSlot(id, slot, now);
				}
			});
			startLocks(userId, placement, now);
			return myRoutes(userId, account, now);
		});
	}

	/** Removing is always allowed; the freed slot keeps its cooldown. */
	void remove(long userId, long routeId) {
		tx.executeWithoutResult(status -> {
			lock(userId);
			if (!store.remove(userId, routeId, clock.instant())) {
				throw notFound();
			}
		});
	}

	private Account lock(long userId) {
		return store.lockAccount(userId).filter(a -> a.bannedAt() == null).orElseThrow(RouteService::signedOut);
	}

	/** Route problems, then overlaps with the user's other routes (checked under the user lock). */
	private List<Problem> problems(long userId, RoutePlan plan, Long exceptRouteId) {
		List<Problem> problems = new ArrayList<>(routeProblems(plan));
		if (problems.isEmpty()) {
			store.overlaps(userId, plan.segmentIds(), exceptRouteId)
				.forEach(o -> problems.add(Problem.overlap(o.id(), o.slot(), o.name())));
		}
		return problems;
	}

	/** Main roads only, a way there and back, and the 40 km cap on the longer direction's actual length. */
	static List<Problem> routeProblems(RoutePlan plan) {
		List<Problem> problems = new ArrayList<>();
		List<SnappedPoint> points = plan.points();
		for (int i = 0; i < points.size(); i++) {
			SnappedPoint p = points.get(i);
			String which = i == 0 ? "start" : i == points.size() - 1 ? "end" : "waypoint";
			if (p.snapped() == null) {
				problems.add(Problem.noRoadNearby(which));
			}
			else if (!which.equals("waypoint") && p.offsetM() > SIDE_ROAD_M) {
				problems.add(Problem.sideRoad(which, p.snapped()));
			}
		}
		if (!problems.isEmpty()) {
			return problems;
		}
		if (plan.out() == null) {
			problems.add(Problem.of(Problem.Code.NO_ROUTE));
			return problems;
		}
		if (plan.back() == null) {
			problems.add(Problem.of(Problem.Code.NO_WAY_BACK));
		}
		if (plan.longerLengthM() > MAX_LENGTH_M) {
			problems.add(Problem.tooLong(plan.longerLengthM(), MAX_LENGTH_M));
		}
		return problems;
	}

	private Placement place(List<StoredRoute> routes, Long moving, int slot, List<Problem> problems) {
		Map<Long, Integer> current = routes.stream().collect(Collectors.toMap(StoredRoute::id, StoredRoute::slot));
		Optional<Placement> placement = Slots.place(current, moving, moving == null ? NEW_ROUTE : moving, slot);
		if (placement.isEmpty()) {
			problems.add(Problem.of(Problem.Code.SLOTS_FULL));
			return new Placement(Map.of(), Set.of());
		}
		return placement.get();
	}

	private void checkLocks(long userId, Placement placement, Instant now, List<Problem> problems) {
		Map<Integer, Instant> starts = store.lockStarts(userId, now);
		placement.touched().stream().sorted().forEach(slot -> {
			Lock lock = new Lock(slot, starts.get(slot));
			if (!lock.changeable(now)) {
				problems.add(Problem.slotLocked(slot, lock.lockedUntil()));
			}
		});
	}

	private void startLocks(long userId, Placement placement, Instant now) {
		Map<Integer, Instant> starts = store.lockStarts(userId, now);
		for (int slot : placement.touched()) {
			if (new Lock(slot, starts.get(slot)).changeStartsLock(now)) {
				store.recordSlotChange(userId, slot, now);
			}
		}
	}

	private void moveShifted(List<StoredRoute> routes, Placement placement, Instant now) {
		for (StoredRoute r : routes) {
			Integer slot = placement.slots().get(r.id());
			if (slot != null && slot != r.slot()) {
				store.moveToSlot(r.id(), slot, now);
			}
		}
	}

	private static void refuseIf(List<Problem> problems) {
		if (!problems.isEmpty()) {
			throw new RouteRefusedException(problems);
		}
	}

	private NewRoute toStore(RoutePlan plan) {
		List<LatLon> points = plan.points().stream().map(SnappedPoint::snapped).toList();
		return new NewRoute(roads.name(points.getFirst(), points.getLast()), points.getFirst(), points.getLast(),
				points.subList(1, points.size() - 1), plan.out().line(), plan.back().line(), plan.out().lengthM(),
				plan.back().lengthM(), plan.segmentIds());
	}

	private MyRoutes myRoutes(long userId, Account account, Instant now) {
		List<StoredRoute> active = store.active(userId);
		Map<Long, Busiest> busiest = stretches.busiest(active.stream().map(StoredRoute::id).toList());
		List<RouteView> routes = active.stream().map(r -> view(r, busiest.get(r.id()))).toList();
		Map<Integer, Instant> starts = store.lockStarts(userId, now);
		List<SlotView> slots = new ArrayList<>();
		for (int slot = 1; slot <= Slots.COUNT; slot++) {
			Lock lock = new Lock(slot, starts.get(slot));
			slots.add(new SlotView(slot, lock.free(now) ? null : lock.lockedUntil(),
					lock.inGrace(now) ? lock.startedAt().plus(Slots.GRACE) : null));
		}
		return new MyRoutes(routes, slots, account.liveAt());
	}

	private static RouteView view(List<StoredRoute> routes, long id) {
		Map<Long, StoredRoute> byId = new HashMap<>(routes.stream().collect(Collectors.toMap(StoredRoute::id, Function.identity())));
		return view(byId.get(id), null); // just saved: its stretches are known after the next scoring run
	}

	private static RouteView view(StoredRoute r, Busiest busiest) {
		return new RouteView(r.id(), r.slot(), r.name(), r.start(), r.end(), List.copyOf(r.waypoints()), Views.line(r.out()),
				Views.line(r.back()), r.lengthOutM(), r.lengthBackM(), r.createdAt(), r.updatedAt(), busiest);
	}

	private static ResponseStatusException signedOut() {
		return new ResponseStatusException(HttpStatus.UNAUTHORIZED);
	}

	private static ResponseStatusException notFound() {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "no such route");
	}

}
