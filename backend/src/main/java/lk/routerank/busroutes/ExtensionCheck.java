package lk.routerank.busroutes;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import lk.routerank.roads.LatLon;

/**
 * When a route extends a bus route (see the Spec, "Existing bus routes and extensions"): it runs along the bus up
 * to one of its ends, or starts within 500 m of one, and carries on past it. A route that leaves the bus partway is
 * a new route. The route is cut at its nearest point to the bus's end: one side must be on the bus (or very short)
 * and the other side new road.
 */
final class ExtensionCheck {

	/** The route must pass this close to the bus's end. */
	static final double NEAR_END_M = 500;

	/** A side this short counts as "starting at the end" whatever it runs on. */
	static final double SHORT_M = 500;

	/** The new part must be at least this long. */
	static final double MIN_NEW_M = 500;

	/** A point this close to the bus's line is on the bus. */
	static final double ON_BUS_M = 30;

	/** The side running along the bus must be at least this much on it... */
	static final double ALONG_SHARE = 0.7;

	/** ...and the new part at most this much. */
	static final double NEW_SHARE = 0.3;

	private ExtensionCheck() {
	}

	/**
	 * An active bus route, as the check needs it.
	 *
	 * @param out the bus's way from its start to its end
	 * @param back its way from its end back to its start
	 */
	record Bus(long id, String number, String startName, String endName, LatLon start, LatLon end, List<LatLon> out,
			List<LatLon> back, double lengthOutM, double lengthBackM, Set<Long> segmentIds, Lines.Near near) {

		Bus(long id, String number, String startName, String endName, LatLon start, LatLon end, List<LatLon> out,
				List<LatLon> back, double lengthOutM, double lengthBackM, Set<Long> segmentIds) {
			this(id, number, startName, endName, start, end, out, back, lengthOutM, lengthBackM, segmentIds,
					new Lines.Near(List.of(out, back), ON_BUS_M));
		}

		String name() {
			return startName + " → " + endName;
		}

	}

	/**
	 * A route that extends a bus.
	 *
	 * @param atBusEnd the route extends the bus at its end ({@code end}); otherwise at its start
	 * @param throughEnd the route's way there runs along the bus into that end and then beyond it; otherwise it
	 * comes from beyond, into that end and then along the bus
	 * @param newOutM the new part's length on the route's way there
	 * @param newBackM the new part's length on the route's way back
	 * @param totalOutM bus + new part, travelling the way the route's way there goes
	 * @param totalBackM bus + new part, travelling the way the route's way back goes
	 * @param alongM how much of the route runs along the bus (which match wins when two could)
	 */
	record Match(Bus bus, boolean atBusEnd, boolean throughEnd, double newOutM, double newBackM, double totalOutM,
			double totalBackM, double alongM) {

		double longerTotalM() {
			return Math.max(totalOutM, totalBackM);
		}

		/** The bus length on the longer total's direction, as W18 shows it ("bus 99 (21.4 km) + new part"). */
		double busLengthM() {
			return totalOutM >= totalBackM ? totalOutM - newOutM : totalBackM - newBackM;
		}

		double newLengthM() {
			return totalOutM >= totalBackM ? newOutM : newBackM;
		}

	}

	static Optional<Match> match(Bus bus, List<LatLon> out, List<LatLon> back) {
		Match best = null;
		for (boolean atBusEnd : new boolean[] { true, false }) {
			Match m = matchAt(bus, atBusEnd, out, back);
			if (m != null && (best == null || m.alongM() > best.alongM())) {
				best = m;
			}
		}
		return Optional.ofNullable(best);
	}

	private static Match matchAt(Bus bus, boolean atBusEnd, List<LatLon> out, List<LatLon> back) {
		LatLon end = atBusEnd ? bus.end() : bus.start();
		Lines.Cut cut = Lines.cut(out, end);
		if (cut.distanceM() > NEAR_END_M) {
			return null;
		}
		Side before = side(bus, cut.before());
		Side after = side(bus, cut.after());
		boolean throughEnd;
		if (before.along() && after.isNew()) {
			throughEnd = true;
		}
		else if (after.along() && before.isNew()) {
			throughEnd = false;
		}
		else {
			return null;
		}
		double newOut = throughEnd ? after.lengthM() : before.lengthM();
		// The way back crosses the same end the other way round
		Lines.Cut backCut = Lines.cut(back, end);
		double newBack = Lines.lengthM(throughEnd ? backCut.before() : backCut.after());
		// The bus direction that arrives at this end, and the one that leaves it
		double busInto = atBusEnd ? bus.lengthOutM() : bus.lengthBackM();
		double busFrom = atBusEnd ? bus.lengthBackM() : bus.lengthOutM();
		double totalOut = (throughEnd ? busInto : busFrom) + newOut;
		double totalBack = (throughEnd ? busFrom : busInto) + newBack;
		double alongM = throughEnd ? before.lengthM() * before.share() : after.lengthM() * after.share();
		return new Match(bus, atBusEnd, throughEnd, newOut, newBack, totalOut, totalBack, alongM);
	}

	private static Side side(Bus bus, List<LatLon> part) {
		return new Side(Lines.lengthM(part), bus.near().shareOf(part));
	}

	/** One side of the cut: its length and the share of it on the bus. */
	private record Side(double lengthM, double share) {

		boolean along() {
			return lengthM < SHORT_M || share >= ALONG_SHARE;
		}

		boolean isNew() {
			return lengthM >= MIN_NEW_M && share <= NEW_SHARE;
		}

	}

}
