package lk.routerank.busroutes;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import lk.routerank.admin.Audit;
import lk.routerank.busroutes.BusRouteStore.Drawn;
import lk.routerank.busroutes.BusRouteStore.Listed;
import lk.routerank.roads.LatLon;
import lk.routerank.roads.RoutePlan;
import lk.routerank.roads.RoutePlan.SnappedPoint;
import lk.routerank.roads.Roads;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Existing bus routes and the extension rules (see the Spec, "Existing bus routes and extensions").
 *
 * <p>Admins draw bus routes with the same main-road routing as user routes. The way back is the fastest unless the
 * admin gives it waypoints of its own, for a bus that comes back on a different road; bus routes have no length
 * limit. An extension of one must fit in 40 km in each direction, bus
 * included, and only its new part scores.
 */
@Service
public class BusRoutes {

	/** Bus + extension, on the longer direction. */
	public static final double MAX_EXTENDED_M = 40_000;

	/** Bus routes bend more than a voter's route, so their form allows more waypoints. */
	static final int MAX_WAYPOINTS = 25;

	/** A start or end further than this from the main road it snaps to is on a side road (as for routes). */
	static final double SIDE_ROAD_M = 30;

	private final BusRouteStore store;

	private final Roads roads;

	private final Audit audit;

	private final TransactionTemplate tx;

	private final Clock clock;

	/** The active bus routes, reloaded whenever an admin changes one. */
	private volatile List<ExtensionCheck.Bus> active;

	BusRoutes(BusRouteStore store, Roads roads, Audit audit, TransactionTemplate tx, Clock clock) {
		this.store = store;
		this.roads = roads;
		this.audit = audit;
		this.tx = tx;
		this.clock = clock;
	}

	/** The bus route this route would best extend, if it extends one (W18, W18b). */
	public Optional<Extension> extensionFor(RoutePlan plan) {
		if (plan.out() == null || plan.back() == null) {
			return Optional.empty();
		}
		return active().stream()
			.flatMap(bus -> ExtensionCheck.match(bus, plan.out().line(), plan.back().line()).stream())
			.max(ExtensionCheck.Match.BEST)
			.map(this::extension);
	}

	/** This route as an extension of that bus route, if it is one. */
	public Optional<Extension> extension(long busRouteId, RoutePlan plan) {
		if (plan.out() == null || plan.back() == null) {
			return Optional.empty();
		}
		return active().stream()
			.filter(bus -> bus.id() == busRouteId)
			.findFirst()
			.flatMap(bus -> ExtensionCheck.match(bus, plan.out().line(), plan.back().line()))
			.map(this::extension);
	}

	private Extension extension(ExtensionCheck.Match m) {
		ExtensionCheck.Bus bus = m.bus();
		String farEnd = m.atBusEnd() ? bus.startName() : bus.endName();
		return new Extension(bus.id(), bus.number(), bus.name(), m.busLengthM(), m.newLengthM(), m.longerTotalM(),
				m.longerTotalM() <= MAX_EXTENDED_M, farEnd, m.throughEnd(), bus.segmentIds());
	}

	/** Reloads the active bus routes on next use (after a change made outside this class, e.g. in tests). */
	void reload() {
		active = null;
	}

	private List<ExtensionCheck.Bus> active() {
		List<ExtensionCheck.Bus> buses = active;
		if (buses == null) {
			buses = store.active();
			active = buses;
		}
		return buses;
	}

	// Admin: drawing, adding, redrawing and retiring bus routes

	List<Listed> list() {
		return store.all();
	}

	/** Works a bus route out from the admin's points, with anything that would stop it being saved. */
	Drawing draw(BusRouteInput input) {
		RoutePlan plan = roads.plan(input.points(), input.backWaypoints());
		List<String> problems = new ArrayList<>();
		List<SnappedPoint> points = plan.points();
		for (int i = 0; i < points.size(); i++) {
			SnappedPoint p = points.get(i);
			String which = i == 0 ? "start" : i == points.size() - 1 ? "end" : "waypoint";
			if (p.snapped() == null) {
				problems.add("NO_ROAD_NEARBY:" + which);
			}
			else if (!which.equals("waypoint") && p.offsetM() > SIDE_ROAD_M) {
				problems.add("SIDE_ROAD:" + which);
			}
		}
		if (plan.backPoints().stream().anyMatch(p -> p.snapped() == null)) {
			problems.add("NO_ROAD_NEARBY:backWaypoint");
		}
		if (problems.isEmpty() && plan.out() == null) {
			problems.add("NO_ROUTE");
		}
		else if (problems.isEmpty() && plan.back() == null) {
			problems.add("NO_WAY_BACK");
		}
		if (input.number() != null && !input.number().isBlank() && store.numberTaken(input.number().strip(), input.id())) {
			problems.add("NUMBER_TAKEN");
		}
		List<LatLon> snapped = points.stream().map(p -> p.snapped() != null ? p.snapped() : p.input()).toList();
		boolean routed = plan.out() != null && plan.back() != null;
		String startPlace = roads.placeName(snapped.getFirst());
		String endPlace = roads.placeName(snapped.getLast());
		String startName = Objects.requireNonNullElse(blankToNull(input.startName()), Objects.requireNonNullElse(startPlace, "Start"));
		String endName = Objects.requireNonNullElse(blankToNull(input.endName()), Objects.requireNonNullElse(endPlace, "End"));
		List<String> towns = !routed ? List.of() : roads.townsAlong(plan.out().line(), 8)
			.stream()
			.filter(t -> !t.equals(startPlace) && !t.equals(endPlace) && !t.equals(startName) && !t.equals(endName))
			.limit(6)
			.toList();
		List<LatLon> backSnapped = plan.backPoints().stream().map(p -> p.snapped() != null ? p.snapped() : p.input()).toList();
		return new Drawing(snapped, backSnapped, routed ? plan.out().line() : List.of(),
				routed ? plan.back().line() : List.of(), routed ? plan.out().lengthM() : 0,
				routed ? plan.back().lengthM() : 0, routed ? plan.back().leavesVia() : List.of(), startName, endName,
				towns, routed ? Set.copyOf(plan.segmentIds()) : Set.of(), problems);
	}

	long add(BusRouteInput input, long adminId) {
		Drawing d = drawOrRefuse(input);
		Instant now = clock.instant();
		long id = tx.execute(status -> {
			long newId = store.insert(drawn(input, d), now);
			audit.record(adminId, "bus_route.add", "bus_route:" + newId, null, summary(input.number(), d), input.reason(), now);
			return newId;
		});
		active = null;
		return id;
	}

	void redraw(long id, BusRouteInput input, long adminId) {
		Listed before = store.find(id).orElseThrow(BusRoutes::notFound);
		if (before.retiredAt() != null) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "retired bus routes can't be changed");
		}
		Drawing d = drawOrRefuse(new BusRouteInput(id, input.number(), input.startName(), input.endName(), input.start(),
				input.end(), input.waypoints(), input.backWaypoints(), input.reason()));
		Instant now = clock.instant();
		tx.executeWithoutResult(status -> {
			if (store.extensionCount(id) > 0) {
				throw new ResponseStatusException(HttpStatus.CONFLICT,
						"routes extend this bus route: retire it and add a new one instead");
			}
			store.update(id, drawn(input, d), now);
			audit.record(adminId, "bus_route.redraw", "bus_route:" + id,
					summary(before.number(), before.startName(), before.endName(), before.lengthOutM(), before.lengthBackM()),
					summary(input.number(), d), input.reason(), now);
		});
		active = null;
	}

	void retire(long id, String reason, long adminId) {
		Instant now = clock.instant();
		tx.executeWithoutResult(status -> {
			if (!store.retire(id, now)) {
				throw notFound();
			}
			audit.record(adminId, "bus_route.retire", "bus_route:" + id, Map.of("retired", false),
					Map.of("retired", true), reason, now);
		});
		active = null;
	}

	private Drawing drawOrRefuse(BusRouteInput input) {
		if (input.number() == null || input.number().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "number is required");
		}
		Drawing d = draw(input);
		if (!d.problems().isEmpty()) {
			throw new ResponseStatusException(HttpStatusCode.valueOf(422), String.join(", ", d.problems()));
		}
		return d;
	}

	private static Drawn drawn(BusRouteInput input, Drawing d) {
		return new Drawn(input.number().strip(), d.startName(), d.endName(), d.points().getFirst(), d.points().getLast(),
				d.points().subList(1, d.points().size() - 1), d.backWaypoints(), d.out(), d.back(), d.lengthOutM(),
				d.lengthBackM(), d.backVia(), d.towns(), d.segmentIds());
	}

	private static Map<String, Object> summary(String number, Drawing d) {
		return summary(number.strip(), d.startName(), d.endName(), d.lengthOutM(), d.lengthBackM());
	}

	private static Map<String, Object> summary(String number, String startName, String endName, double out, double back) {
		return Map.of("number", number, "name", startName + " → " + endName, "lengthOutM", Math.round(out),
				"lengthBackM", Math.round(back));
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.strip();
	}

	private static ResponseStatusException notFound() {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "no such bus route");
	}

	/**
	 * A route as an extension of a bus route (W18): bus + new part on the longer direction.
	 *
	 * @param busLengthM the bus's length on that direction
	 * @param newLengthM the new part's length on that direction
	 * @param totalM bus + new part on the longer direction, which must be at most 40 km
	 * @param allowed whether {@code totalM} fits in 40 km; "Extend" is blocked otherwise (W18b)
	 * @param farEndName the end of the bus the extension doesn't touch, which names the extended route
	 * @param newPartLast whether the route's way there runs along the bus first and the new part last (so the
	 * route's end is the new end); otherwise its start is
	 * @param busSegmentIds the segments the bus runs on: an extension's segments on these don't score
	 */
	public record Extension(long busRouteId, String number, String busName, double busLengthM, double newLengthM,
			double totalM, boolean allowed, String farEndName, boolean newPartLast, Set<Long> busSegmentIds) {
	}

	/**
	 * A bus route worked out from an admin's points.
	 *
	 * @param backWaypoints the way back's own waypoints, snapped, end to start (empty: the fastest way back)
	 * @param backVia the roads the way back uses where it leaves the way there ("Duplication Road")
	 * @param problems codes that stop it being saved: {@code SIDE_ROAD:start}, {@code NO_WAY_BACK},
	 * {@code NUMBER_TAKEN}…
	 */
	record Drawing(List<LatLon> points, List<LatLon> backWaypoints, List<LatLon> out, List<LatLon> back,
			double lengthOutM, double lengthBackM, List<String> backVia, String startName, String endName, List<String> towns, Set<Long> segmentIds, List<String> problems) {
	}

}
