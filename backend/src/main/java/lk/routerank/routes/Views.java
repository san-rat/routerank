package lk.routerank.routes;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import lk.routerank.roads.LatLon;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;

/** What the routes API returns. Lines are GeoJSON-style {@code [lon, lat]} pairs, simplified to about 3 m. */
final class Views {

	/** ~3 m at Sri Lanka's latitude: keeps every bend of the road, drops points along straight stretches. */
	private static final double SIMPLIFY_DEGREES = 0.00003;

	private static final GeometryFactory GEOMETRY = new GeometryFactory();

	private Views() {
	}

	static double[][] line(List<LatLon> points) {
		if (points.size() < 3) {
			return points.stream().map(p -> new double[] { round(p.lon()), round(p.lat()) }).toArray(double[][]::new);
		}
		Coordinate[] coords = points.stream().map(p -> new Coordinate(p.lon(), p.lat())).toArray(Coordinate[]::new);
		Coordinate[] simple = DouglasPeuckerSimplifier.simplify(GEOMETRY.createLineString(coords), SIMPLIFY_DEGREES)
			.getCoordinates();
		double[][] line = new double[simple.length][];
		for (int i = 0; i < simple.length; i++) {
			line[i] = new double[] { round(simple[i].x), round(simple[i].y) };
		}
		return line;
	}

	private static double round(double degrees) {
		return Math.round(degrees * 1e6) / 1e6;
	}

	/**
	 * The route worked out from the points, for the preview step (W17), with every problem that would block
	 * saving except the slot cooldown (which depends on the slot, chosen on the next step).
	 *
	 * @param points start, waypoints and end snapped to main roads (the input point where there is no road)
	 * @param out the way there; empty when there is none
	 * @param back the way back; empty when there is none
	 * @param backLeaves stretches where the way back leaves the way there (drawn dashed)
	 * @param backVia roads the way back uses there ("Way back uses Duplication Road")
	 * @param name the auto-name ("Pettah → Horana"); empty when there is no route
	 */
	record Preview(List<LatLon> points, double[][] out, double[][] back, List<double[][]> backLeaves,
			List<String> backVia, double lengthOutM, double lengthBackM, String name, List<Problem> problems) {
	}

	/**
	 * One of the user's routes.
	 * @param waypoints the drag points, snapped
	 */
	record RouteView(long id, int slot, String name, LatLon start, LatLon end, List<LatLon> waypoints, double[][] out,
			double[][] back, double lengthOutM, double lengthBackM, Instant createdAt, Instant updatedAt) {
	}

	/**
	 * @param lockedUntil when the slot can change again; absent when it can change now
	 * @param graceUntil the end of the 15-minute window after the change that locked it, if still open
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record SlotView(@Schema(requiredMode = RequiredMode.REQUIRED) int slot, Instant lockedUntil, Instant graceUntil) {
	}

	/**
	 * My routes.
	 * @param routes the user's routes that are not removed, by slot
	 * @param slots all three slots and their cooldowns
	 * @param countsFrom when the account's votes start counting (24 hours after sign-up)
	 */
	record MyRoutes(List<RouteView> routes, List<SlotView> slots, Instant countsFrom) {
	}

	/** Why a save was refused (HTTP 422). */
	record Refused(List<Problem> problems) {
	}

}
