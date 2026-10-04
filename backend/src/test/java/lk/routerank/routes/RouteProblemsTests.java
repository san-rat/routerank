package lk.routerank.routes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import lk.routerank.roads.LatLon;
import lk.routerank.roads.RoutePlan;
import lk.routerank.roads.RoutePlan.Leg;
import lk.routerank.roads.RoutePlan.SnappedPoint;
import lk.routerank.routes.Problem.Code;
import org.junit.jupiter.api.Test;

/** The route rules on hand-made plans, for cases the Colombo test roads can't produce (40 km, no way back). */
class RouteProblemsTests {

	static final LatLon A = new LatLon(6.93, 79.85);

	static final LatLon B = new LatLon(6.90, 79.86);

	static SnappedPoint on(LatLon p) {
		return new SnappedPoint(p, p, 2);
	}

	static Leg leg(double lengthM) {
		return new Leg(List.of(A, B), lengthM, List.of(), List.of());
	}

	static List<Code> codes(RoutePlan plan) {
		return RouteService.routeProblems(plan).stream().map(Problem::code).toList();
	}

	@Test
	void aRouteOnMainRoadsWithAWayBackUnder40KmIsFine() {
		assertThat(codes(new RoutePlan(List.of(on(A), on(B)), leg(12_000), leg(13_000), Set.of(1L)))).isEmpty();
	}

	@Test
	void theLongerDirectionCountsForThe40KmCap() {
		// Way there 39 km, way back 41 km (around a one-way system): too long
		List<Problem> problems = RouteService.routeProblems(
				new RoutePlan(List.of(on(A), on(B)), leg(39_000), leg(41_000), Set.of(1L)));
		assertThat(problems).extracting(Problem::code).containsExactly(Code.TOO_LONG);
		assertThat(problems.getFirst().lengthM()).isEqualTo(41_000);
		assertThat(problems.getFirst().limitM()).isEqualTo(40_000);
		// Exactly 40 km is allowed
		assertThat(codes(new RoutePlan(List.of(on(A), on(B)), leg(40_000), leg(40_000), Set.of(1L)))).isEmpty();
	}

	@Test
	void noWayBackOnMainRoadsBlocksSaving() {
		assertThat(codes(new RoutePlan(List.of(on(A), on(B)), leg(5_000), null, Set.of(1L))))
			.containsExactly(Code.NO_WAY_BACK);
	}

	@Test
	void pointsThatCantBeJoinedAreReported() {
		assertThat(codes(new RoutePlan(List.of(on(A), on(B)), null, null, Set.of()))).containsExactly(Code.NO_ROUTE);
	}

	@Test
	void aStartOrEndOnASideRoadOffersTheNearestMainRoad() {
		LatLon road = new LatLon(6.9301, 79.8502);
		List<Problem> problems = RouteService.routeProblems(new RoutePlan(
				List.of(new SnappedPoint(A, road, 31), on(B)), leg(5_000), leg(5_000), Set.of(1L)));
		assertThat(problems).extracting(Problem::code).containsExactly(Code.SIDE_ROAD);
		assertThat(problems.getFirst().point()).isEqualTo("start");
		assertThat(problems.getFirst().nearest()).isEqualTo(road);
	}

	@Test
	void waypointsSnapToTheNearestMainRoadWithoutComplaint() {
		SnappedPoint dragged = new SnappedPoint(new LatLon(6.92, 79.855), new LatLon(6.9205, 79.855), 55);
		assertThat(codes(new RoutePlan(List.of(on(A), dragged, on(B)), leg(5_000), leg(5_000), Set.of(1L)))).isEmpty();
	}

	@Test
	void aPointWithNoMainRoadNearbyIsReported() {
		SnappedPoint sea = new SnappedPoint(new LatLon(6.9, 79.7), null, Double.POSITIVE_INFINITY);
		List<Problem> problems = RouteService.routeProblems(new RoutePlan(List.of(on(A), sea), null, null, Set.of()));
		assertThat(problems).extracting(Problem::code).containsExactly(Code.NO_ROAD_NEARBY);
		assertThat(problems.getFirst().point()).isEqualTo("end");
	}

}
