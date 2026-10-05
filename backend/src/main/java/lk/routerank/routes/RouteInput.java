package lk.routerank.routes;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lk.routerank.roads.LatLon;

/**
 * What the browser sends for a route: points only, never geometry. The server works the route out again from
 * these on every save (the preview it showed is never trusted).
 *
 * @param slot the slot to save into (1–3); ignored by the preview
 * @param routeId the route being edited, so the preview doesn't report it as overlapping itself; ignored on save
 * @param extend saves: the bus route to extend, when the user chose "Extend" at the Bus check (W18)
 * @param turnstile saves: the Turnstile token for the "save" action
 * @param device saves: FingerprintJS's visitorId; only an HMAC of it is stored
 * @param website saves: the honeypot field, hidden from people; only a bot fills it in
 */
record RouteInput(@NotNull @Valid LatLon start, @NotNull @Valid LatLon end,
		@NotNull @Size(max = RouteInput.MAX_WAYPOINTS) List<@NotNull @Valid LatLon> waypoints,
		@Min(1) @Max(3) Integer slot, Long routeId, Long extend, @Size(max = 2048) String turnstile,
		@Size(max = 64) String device, @Size(max = 200) String website) {

	/** A preview or a test: just the points. */
	RouteInput(LatLon start, LatLon end, List<LatLon> waypoints, Integer slot, Long routeId) {
		this(start, end, waypoints, slot, routeId, null, null, null, null);
	}

	static final int MAX_WAYPOINTS = 8;

	/** Start, waypoints and end, in order. */
	List<LatLon> points() {
		return java.util.stream.Stream.of(List.of(start), waypoints, List.of(end)).flatMap(List::stream).toList();
	}

}
