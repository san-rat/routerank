package lk.routerank.roads;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.graphhopper.ResponsePath;
import lk.routerank.roads.RoutePlan.Leg;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

/**
 * Where the way back leaves the way there. On a two-way road it follows the same road back; around a one-way
 * street it takes another road (Galle Road one way, Duplication Road the other), and the preview draws those
 * stretches dashed with a note naming the roads.
 */
final class WayBack {

	/** Closer than this to the way there counts as the same road (two carriageways of one road are ~10–20 m apart). */
	static final double SAME_ROAD_M = 25;

	/** Shorter departures are junction geometry, not a different road. */
	static final double MIN_LEAVE_M = 60;

	/** A road is named in the note if it carries at least this share of a stretch. */
	static final double MAIN_ROAD_SHARE = 0.3;

	static final int MAX_NAMES = 2;

	/** Points are added along long straight edges so a departure between two far-apart points isn't missed. */
	private static final double STEP_M = 20;

	private static final double METRES_PER_DEGREE = 111_320;

	private static final GeometryFactory GEOMETRY = new GeometryFactory();

	private WayBack() {
	}

	static Leg describe(List<LatLon> out, ResponsePath back) {
		List<LatLon> line = Roads.line(back.getPoints());
		LineString outLine = lineString(out);

		// Walk the way back in small steps, remembering which original edge each step lies on
		List<LatLon> steps = new ArrayList<>();
		List<Integer> edgeOf = new ArrayList<>();
		for (int i = 0; i < line.size() - 1; i++) {
			LatLon a = line.get(i);
			LatLon b = line.get(i + 1);
			int n = Math.max(1, (int) Math.ceil(Roads.distanceM(a, b) / STEP_M));
			for (int k = 0; k < n; k++) {
				double f = (double) k / n;
				steps.add(new LatLon(a.lat() + (b.lat() - a.lat()) * f, a.lon() + (b.lon() - a.lon()) * f));
				edgeOf.add(i);
			}
		}
		if (!line.isEmpty()) {
			steps.add(line.getLast());
			edgeOf.add(Math.max(0, line.size() - 2));
		}

		String[] nameOfEdge = Roads.edgeNames(back.getPathDetails(), Math.max(0, line.size() - 1));
		List<List<LatLon>> leaves = new ArrayList<>();
		Map<String, Double> via = new LinkedHashMap<>();
		int runStart = -1;
		for (int i = 0; i <= steps.size(); i++) {
			boolean off = i < steps.size() && distanceM(outLine, steps.get(i)) > SAME_ROAD_M;
			if (off && runStart < 0) {
				runStart = i;
			}
			else if (!off && runStart >= 0) {
				// Include the on-road neighbours so the dashed line joins the solid one
				int from = Math.max(0, runStart - 1);
				int to = Math.min(steps.size() - 1, i);
				List<LatLon> run = steps.subList(from, to + 1);
				double runLength = length(run);
				if (runLength >= MIN_LEAVE_M) {
					leaves.add(List.copyOf(run));
					// Name the roads carrying most of this stretch, not every junction road it brushes past
					Map<String, Double> byName = new LinkedHashMap<>();
					for (int k = from; k < to; k++) {
						String name = nameOfEdge.length == 0 ? null : nameOfEdge[edgeOf.get(k)];
						if (name != null) {
							byName.merge(name, Roads.distanceM(steps.get(k), steps.get(k + 1)), Double::sum);
						}
					}
					byName.forEach((name, m) -> {
						if (m >= MAIN_ROAD_SHARE * runLength) {
							via.merge(name, m, Double::sum);
						}
					});
				}
				runStart = -1;
			}
		}
		List<String> names = via.entrySet().stream()
			.sorted(Map.Entry.<String, Double>comparingByValue().reversed())
			.limit(MAX_NAMES)
			.map(Map.Entry::getKey)
			.toList();
		return new Leg(line, back.getDistance(), leaves, names);
	}

	private static LineString lineString(List<LatLon> points) {
		Coordinate[] coords = points.stream().map(p -> new Coordinate(p.lon(), p.lat())).toArray(Coordinate[]::new);
		return coords.length >= 2 ? GEOMETRY.createLineString(coords) : GEOMETRY.createLineString();
	}

	/** Approximate metres from a point to the line (fine at Sri Lanka's latitude, 6–10° N). */
	private static double distanceM(LineString line, LatLon p) {
		if (line.isEmpty()) {
			return Double.POSITIVE_INFINITY;
		}
		return line.distance(GEOMETRY.createPoint(new Coordinate(p.lon(), p.lat()))) * METRES_PER_DEGREE;
	}

	private static double length(List<LatLon> run) {
		double total = 0;
		for (int i = 1; i < run.size(); i++) {
			total += Roads.distanceM(run.get(i - 1), run.get(i));
		}
		return total;
	}

}
