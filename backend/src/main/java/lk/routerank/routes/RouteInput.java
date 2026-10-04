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
 */
record RouteInput(@NotNull @Valid LatLon start, @NotNull @Valid LatLon end,
		@NotNull @Size(max = RouteInput.MAX_WAYPOINTS) List<@NotNull @Valid LatLon> waypoints,
		@Min(1) @Max(3) Integer slot, Long routeId) {

	static final int MAX_WAYPOINTS = 8;

	/** Start, waypoints and end, in order. */
	List<LatLon> points() {
		return java.util.stream.Stream.of(List.of(start), waypoints, List.of(end)).flatMap(List::stream).toList();
	}

}
