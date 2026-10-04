package lk.routerank.busroutes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import lk.routerank.busroutes.ExtensionCheck.Bus;
import lk.routerank.busroutes.ExtensionCheck.Match;
import lk.routerank.roads.LatLon;
import org.junit.jupiter.api.Test;

/**
 * When a route extends a bus route, on made-up straight roads: a bus running 11 km due south, and routes that run
 * along it, start at its end, or leave it partway.
 */
class ExtensionCheckTests {

	static final double TOP = 6.935;

	static final double BOTTOM = 6.835;

	static final double LON = 79.85;

	/** About 11.1 km from Pettah due south. */
	static Bus bus(double lengthOutM, double lengthBackM) {
		List<LatLon> out = line(new LatLon(TOP, LON), new LatLon(BOTTOM, LON));
		return new Bus(1, "99", "Pettah", "Makumbura", out.getFirst(), out.getLast(), out, out.reversed(), lengthOutM,
				lengthBackM, Set.of());
	}

	static Bus bus() {
		double length = Lines.lengthM(line(new LatLon(TOP, LON), new LatLon(BOTTOM, LON)));
		return bus(length, length);
	}

	/** A line through the points, with a point every ~100 m like a routed path. */
	static List<LatLon> line(LatLon... points) {
		List<LatLon> line = new ArrayList<>();
		for (int i = 0; i + 1 < points.length; i++) {
			LatLon a = points[i];
			LatLon b = points[i + 1];
			int steps = Math.max(1, (int) (Lines.distanceM(a, b) / 100));
			for (int s = 0; s < steps; s++) {
				double t = (double) s / steps;
				line.add(new LatLon(a.lat() + t * (b.lat() - a.lat()), a.lon() + t * (b.lon() - a.lon())));
			}
		}
		line.add(points[points.length - 1]);
		return line;
	}

	static Match match(List<LatLon> out) {
		return ExtensionCheck.match(bus(), out, out.reversed()).orElse(null);
	}

	@Test
	void aRouteAlongTheBusThatCarriesOnPastItsEndExtendsIt() {
		// From halfway down the bus to its end, then 5.5 km east
		List<LatLon> out = line(new LatLon(6.885, LON), new LatLon(BOTTOM, LON), new LatLon(BOTTOM, 79.90));
		Match m = match(out);
		assertThat(m).isNotNull();
		assertThat(m.atBusEnd()).isTrue();
		assertThat(m.throughEnd()).isTrue();
		assertThat(m.newOutM()).isCloseTo(5_500, within(100.0));
		assertThat(m.newBackM()).isCloseTo(5_500, within(100.0));
		// The whole bus counts, not just the part the route shares
		assertThat(m.longerTotalM()).isCloseTo(11_100 + 5_500, within(150.0));
		assertThat(m.busLengthM()).isCloseTo(11_100, within(50.0));
		assertThat(m.newLengthM()).isCloseTo(5_500, within(100.0));
	}

	@Test
	void aRouteStartingNearTheEndExtendsIt() {
		// Starts 300 m east of the bus's end and heads east
		List<LatLon> out = line(new LatLon(BOTTOM, LON + 0.0027), new LatLon(BOTTOM, 79.90));
		Match m = match(out);
		assertThat(m).isNotNull();
		assertThat(m.atBusEnd()).isTrue();
		assertThat(m.throughEnd()).isTrue();
	}

	@Test
	void aRouteComingTheOtherWayExtendsItToo() {
		// From the east into the bus's end, then up the bus: the new part comes first
		List<LatLon> out = line(new LatLon(BOTTOM, 79.90), new LatLon(BOTTOM, LON), new LatLon(6.885, LON));
		Match m = match(out);
		assertThat(m).isNotNull();
		assertThat(m.throughEnd()).isFalse();
		assertThat(m.newOutM()).isCloseTo(5_500, within(100.0));
	}

	@Test
	void anExtensionAtTheBusStartWorks() {
		// North from Pettah, past the bus's start
		List<LatLon> out = line(new LatLon(6.90, LON), new LatLon(TOP, LON), new LatLon(6.99, LON));
		Match m = match(out);
		assertThat(m).isNotNull();
		assertThat(m.atBusEnd()).isFalse();
		assertThat(m.newOutM()).isCloseTo(6_100, within(100.0));
	}

	@Test
	void aRouteThatLeavesTheBusPartwayIsANewRoute() {
		// Down the bus from Pettah, then east halfway: never reaches the end
		assertThat(match(line(new LatLon(TOP, LON), new LatLon(6.885, LON), new LatLon(6.885, 79.90)))).isNull();
	}

	@Test
	void aRouteAlongTheBusOnlyIsANewRoute() {
		assertThat(match(line(new LatLon(6.90, LON), new LatLon(BOTTOM, LON)))).isNull();
	}

	@Test
	void aRouteFarFromTheBusIsANewRoute() {
		assertThat(match(line(new LatLon(BOTTOM, 79.88), new LatLon(BOTTOM, 79.95)))).isNull();
	}

	@Test
	void aTinyExtensionDoesNotCount() {
		// 200 m past the end is under the 500 m minimum for a new part
		assertThat(match(line(new LatLon(6.885, LON), new LatLon(BOTTOM, LON), new LatLon(BOTTOM, LON + 0.0018))))
			.isNull();
	}

	@Test
	void eachBusDirectionPairsWithTheWayTheRouteTravels() {
		// The bus is longer one way (one-way streets); the route runs down it and on: its way there follows the bus's
		// way out, and its way back the bus's way back
		List<LatLon> out = line(new LatLon(6.885, LON), new LatLon(BOTTOM, LON), new LatLon(BOTTOM, 79.90));
		Match m = ExtensionCheck.match(bus(20_000, 30_000), out, out.reversed()).orElseThrow();
		assertThat(m.totalOutM()).isCloseTo(20_000 + 5_500, within(100.0));
		assertThat(m.totalBackM()).isCloseTo(30_000 + 5_500, within(100.0));
		assertThat(m.longerTotalM()).isEqualTo(m.totalBackM());
		assertThat(m.busLengthM()).isCloseTo(30_000, within(1.0));
	}

	@Test
	void onAShortBusTheEndTheRouteStartsAtWins() {
		// A 1.3 km bus. The route starts at its end, runs back up it to within 300 m of its start, then turns east:
		// that extends it at its end, even though it also passes near its start
		double top = 6.935;
		double bottom = top - 0.012;
		List<LatLon> busLine = line(new LatLon(top, LON), new LatLon(bottom, LON));
		Bus shortBus = new Bus(2, "138", "Masangasweediya", "NewBazzar", busLine.getFirst(), busLine.getLast(), busLine,
				busLine.reversed(), Lines.lengthM(busLine), Lines.lengthM(busLine), Set.of());
		List<LatLon> out = line(new LatLon(bottom, LON), new LatLon(top - 0.0027, LON), new LatLon(top - 0.0027, 79.88));
		Match m = ExtensionCheck.match(shortBus, out, out.reversed()).orElseThrow();
		assertThat(m.atBusEnd()).isTrue();
		assertThat(m.endDistanceM()).isLessThan(1);
	}

	@Test
	void aBusOver40KmCanStillBeMatchedButNeverFits() {
		List<LatLon> out = line(new LatLon(6.885, LON), new LatLon(BOTTOM, LON), new LatLon(BOTTOM, 79.90));
		Match m = ExtensionCheck.match(bus(41_000, 41_000), out, out.reversed()).orElseThrow();
		assertThat(m.longerTotalM()).isGreaterThan(BusRoutes.MAX_EXTENDED_M);
	}

}
