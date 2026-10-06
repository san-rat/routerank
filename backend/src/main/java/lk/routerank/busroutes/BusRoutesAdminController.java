package lk.routerank.busroutes;

import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lk.routerank.auth.Admins;
import lk.routerank.auth.SignedInUser;
import lk.routerank.busroutes.BusRouteStore.Listed;
import lk.routerank.busroutes.BusRoutes.Drawing;
import lk.routerank.roads.LatLon;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin pages for bus routes: draw, add, redraw (only while nothing extends it) and retire. Every change takes a
 * written reason and goes in the audit log.
 */
@RestController
@RequestMapping("/api/admin/bus-routes")
class BusRoutesAdminController {

	private final BusRoutes busRoutes;

	private final Admins admins;

	BusRoutesAdminController(BusRoutes busRoutes, Admins admins) {
		this.busRoutes = busRoutes;
		this.admins = admins;
	}

	@GetMapping
	List<BusRouteView> list(@AuthenticationPrincipal SignedInUser user, HttpSession session) {
		admins.require(user, session);
		return busRoutes.list().stream().map(BusRouteView::of).toList();
	}

	/** Works the route out from the points, both directions, without saving. */
	@PostMapping("/draw")
	DrawingView draw(@AuthenticationPrincipal SignedInUser user, HttpSession session,
			@Valid @RequestBody BusRouteInput input) {
		admins.require(user, session);
		return DrawingView.of(busRoutes.draw(input));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	Created add(@AuthenticationPrincipal SignedInUser user, HttpSession session, @Valid @RequestBody BusRouteInput input) {
		long admin = admins.require(user, session);
		requireReason(input.reason());
		return new Created(busRoutes.add(new BusRouteInput(null, input.number(), input.startName(), input.endName(),
				input.start(), input.end(), input.waypoints(), input.backWaypoints(), input.reason()), admin));
	}

	/** 409 when routes extend it: retire it and add a new one instead. */
	@PutMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void redraw(@AuthenticationPrincipal SignedInUser user, HttpSession session, @PathVariable long id,
			@Valid @RequestBody BusRouteInput input) {
		long admin = admins.require(user, session);
		requireReason(input.reason());
		busRoutes.redraw(id, input, admin);
	}

	/** Retired routes stay on record and their extensions keep counting, but nothing new can extend them. */
	@PostMapping("/{id}/retire")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void retire(@AuthenticationPrincipal SignedInUser user, HttpSession session, @PathVariable long id,
			@Valid @RequestBody Reason reason) {
		long admin = admins.require(user, session);
		busRoutes.retire(id, reason.reason(), admin);
	}

	private static void requireReason(String reason) {
		if (reason == null || reason.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a reason is required");
		}
	}

	record Reason(@NotBlank @Size(max = 500) String reason) {
	}

	record Created(long id) {
	}

	/** Lines are {@code [lon, lat]} pairs. */
	record BusRouteView(long id, String number, String startName, String endName, LatLon start, LatLon end,
			List<LatLon> waypoints, List<LatLon> backWaypoints, double[][] out, double[][] back, double lengthOutM,
			double lengthBackM, List<String> towns, Instant createdAt, Instant updatedAt, Instant retiredAt,
			int extensions) {

		static BusRouteView of(Listed b) {
			return new BusRouteView(b.id(), b.number(), b.startName(), b.endName(), b.start(), b.end(), b.waypoints(),
					b.backWaypoints(), coords(b.out()), coords(b.back()), b.lengthOutM(), b.lengthBackM(), b.towns(), b.createdAt(),
					b.updatedAt(), b.retiredAt(), b.extensions());
		}

	}

	/** @param problems codes that stop it being saved, e.g. {@code SIDE_ROAD:start}, {@code NO_WAY_BACK} */
	record DrawingView(List<LatLon> points, List<LatLon> backWaypoints, double[][] out, double[][] back,
			double lengthOutM, double lengthBackM, List<String> backVia, String startName, String endName,
			List<String> towns, List<String> problems) {

		static DrawingView of(Drawing d) {
			return new DrawingView(d.points(), d.backWaypoints(), coords(d.out()), coords(d.back()), d.lengthOutM(),
					d.lengthBackM(), d.backVia(), d.startName(), d.endName(), d.towns(), d.problems());
		}

	}

	static double[][] coords(List<LatLon> line) {
		return line.stream().map(p -> new double[] { p.lon(), p.lat() }).toArray(double[][]::new);
	}

}
