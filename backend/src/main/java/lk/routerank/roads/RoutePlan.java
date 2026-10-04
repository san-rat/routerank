package lk.routerank.roads;

import java.util.List;
import java.util.Set;

/**
 * A route worked out on main roads: the points snapped to the nearest main road, the way there through them,
 * the way back (end → start), and the road segments it counts for.
 *
 * @param points start, waypoints and end in order; a point that has no main road nearby has {@code snapped} null
 * @param out the way there, or {@code null} when the points can't be joined on main roads
 * @param back the way back, or {@code null} when there is none on main roads (or no way there)
 * @param segmentIds the segments either direction covers by more than half (ADR 0001), each once
 */
public record RoutePlan(List<SnappedPoint> points, Leg out, Leg back, Set<Long> segmentIds) {

	public SnappedPoint start() {
		return points.getFirst();
	}

	public SnappedPoint end() {
		return points.getLast();
	}

	/** The longer of the two directions, which the 40 km cap measures. */
	public double longerLengthM() {
		return Math.max(out == null ? 0 : out.lengthM(), back == null ? 0 : back.lengthM());
	}

	/**
	 * @param offsetM how far the input point was from the main road it snapped to
	 */
	public record SnappedPoint(LatLon input, LatLon snapped, double offsetM) {
	}

	/**
	 * @param line the path, start to end
	 * @param lengthM its actual length
	 * @param leaves for the way back, the stretches where it leaves the way there (drawn dashed); empty otherwise
	 * @param leavesVia the named roads those stretches use, in order ("Way back uses Duplication Road")
	 */
	public record Leg(List<LatLon> line, double lengthM, List<List<LatLon>> leaves, List<String> leavesVia) {
	}

}
