package lk.routerank.busroutes;

import java.util.List;
import java.util.stream.Stream;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lk.routerank.roads.LatLon;

/**
 * What the admin form sends for a bus route: points only, never geometry; the server routes it.
 *
 * @param id the bus route being redrawn (ignored when adding)
 * @param number e.g. "138" or "138/2"; required to save
 * @param startName optional: the start's name, if the nearest place's isn't right ("Pettah")
 * @param endName optional: the end's name
 * @param backWaypoints optional: the way back's own waypoints, end to start, for a bus that comes back on a
 * different road; none means the fastest way back
 * @param reason the admin's written reason, required to save (it goes in the audit log)
 */
record BusRouteInput(Long id, @Size(max = 12) @Pattern(regexp = "[0-9A-Za-z/ -]*") String number,
		@Size(max = 60) String startName, @Size(max = 60) String endName, @NotNull @Valid LatLon start,
		@NotNull @Valid LatLon end,
		@NotNull @Size(max = BusRoutes.MAX_WAYPOINTS) List<@NotNull @Valid LatLon> waypoints,
		@Size(max = BusRoutes.MAX_WAYPOINTS) List<@NotNull @Valid LatLon> backWaypoints,
		@Size(max = 500) String reason) {

	BusRouteInput {
		backWaypoints = backWaypoints == null ? List.of() : backWaypoints;
	}

	List<LatLon> points() {
		return Stream.of(List.of(start), waypoints, List.of(end)).flatMap(List::stream).toList();
	}

}
