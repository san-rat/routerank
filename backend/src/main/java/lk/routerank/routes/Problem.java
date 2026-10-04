package lk.routerank.routes;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import lk.routerank.roads.LatLon;

/**
 * Why a route can't be saved; each code has its own screen in Figma. Only the fields for that code are set.
 *
 * @param point SIDE_ROAD and NO_ROAD_NEARBY: which point ("start", "end" or "waypoint")
 * @param nearest SIDE_ROAD: the nearest main road, offered instead
 * @param lengthM TOO_LONG: the longer direction's length
 * @param limitM TOO_LONG: the cap (40 km)
 * @param routeId OVERLAP: the user's route it overlaps
 * @param slot OVERLAP: that route's slot; SLOT_LOCKED: the locked slot
 * @param routeName OVERLAP: that route's name
 * @param until SLOT_LOCKED: when the slot unlocks
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record Problem(@Schema(requiredMode = RequiredMode.REQUIRED) Code code, String point, LatLon nearest, Double lengthM,
		Double limitM, Long routeId, Integer slot, String routeName, Instant until) {

	enum Code {
		/** A start or end point is off the main roads (W22). */
		SIDE_ROAD,
		/** No main road anywhere near a point (e.g. tapped in the sea). */
		NO_ROAD_NEARBY,
		/** The points can't be joined on main roads. */
		NO_ROUTE,
		/** No way back from end to start on main roads (W33). */
		NO_WAY_BACK,
		/** The longer direction is over 40 km (W21). */
		TOO_LONG,
		/** Shares road with one of the user's own routes (W23). */
		OVERLAP,
		/** The slot changed in the last 24 hours (W24). */
		SLOT_LOCKED,
		/** All three slots hold other routes. */
		SLOTS_FULL
	}

	static Problem of(Code code) {
		return new Problem(code, null, null, null, null, null, null, null, null);
	}

	static Problem sideRoad(String point, LatLon nearest) {
		return new Problem(Code.SIDE_ROAD, point, nearest, null, null, null, null, null, null);
	}

	static Problem noRoadNearby(String point) {
		return new Problem(Code.NO_ROAD_NEARBY, point, null, null, null, null, null, null, null);
	}

	static Problem tooLong(double lengthM, double limitM) {
		return new Problem(Code.TOO_LONG, null, null, lengthM, limitM, null, null, null, null);
	}

	static Problem overlap(long routeId, int slot, String routeName) {
		return new Problem(Code.OVERLAP, null, null, null, null, routeId, slot, routeName, null);
	}

	static Problem slotLocked(int slot, Instant until) {
		return new Problem(Code.SLOT_LOCKED, null, null, null, null, null, slot, null, until);
	}

}
