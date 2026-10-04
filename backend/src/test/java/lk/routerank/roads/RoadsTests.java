package lk.routerank.roads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lk.routerank.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Routing and matching on the Colombo test roads (data/fixtures/colombo.osm.pbf). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RoadsTests {

	// Galle Road is one-way southbound between Kollupitiya and Bambalapitiya; Duplication Road carries the way back
	static final LatLon KOLLUPITIYA = new LatLon(6.9147, 79.8488);

	static final LatLon BAMBALAPITIYA = new LatLon(6.8890, 79.8553);

	static final LatLon BORELLA = new LatLon(6.9147, 79.8775);

	/** In Pettah's side streets, about 150 m from the nearest main road. */
	static final LatLon PETTAH_LANE = new LatLon(6.9355, 79.8500);

	@Autowired
	Roads roads;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransactionTemplate tx;

	@Test
	void plansBothDirectionsOnMainRoads() {
		RoutePlan plan = roads.plan(List.of(KOLLUPITIYA, BAMBALAPITIYA));
		assertThat(plan.out()).isNotNull();
		assertThat(plan.back()).isNotNull();
		assertThat(plan.out().lengthM()).isBetween(3_000.0, 6_000.0);
		assertThat(plan.back().lengthM()).isBetween(3_000.0, 6_000.0);
		assertThat(plan.out().line().getFirst()).isEqualTo(plan.start().snapped());
		assertThat(plan.out().line().getLast()).isEqualTo(plan.end().snapped());
		assertThat(plan.longerLengthM()).isEqualTo(Math.max(plan.out().lengthM(), plan.back().lengthM()));
	}

	@Test
	void oneWayStreetsSendTheWayBackAlongAnotherRoad() {
		RoutePlan plan = roads.plan(List.of(KOLLUPITIYA, BAMBALAPITIYA));
		// Different lengths: the car profile follows Galle Road's one-way tags
		assertThat(plan.out().lengthM()).isNotCloseTo(plan.back().lengthM(), org.assertj.core.data.Offset.offset(200.0));
		assertThat(plan.back().leaves()).isNotEmpty();
		assertThat(plan.back().leavesVia()).isNotEmpty().hasSizeLessThanOrEqualTo(2);
		assertThat(plan.out().leaves()).isEmpty();
	}

	@Test
	void matchesSegmentsOfTheCurrentImportCoveredByEitherDirection() {
		RoutePlan plan = roads.plan(List.of(KOLLUPITIYA, BAMBALAPITIYA));
		assertThat(plan.segmentIds()).isNotEmpty();
		long current = jdbc.sql("SELECT max(id) FROM import_run WHERE finished_at IS NOT NULL").query(Long.class).single();
		List<Long> runs = jdbc.sql("SELECT DISTINCT import_run_id FROM road_segment WHERE id = ANY(:ids)")
			.param("ids", plan.segmentIds().toArray(Long[]::new))
			.query(Long.class)
			.list();
		assertThat(runs).containsExactly(current);

		// Each direction alone covers fewer segments than both together on a one-way pair
		RoutePlan reverse = roads.plan(List.of(BAMBALAPITIYA, KOLLUPITIYA));
		assertThat(reverse.segmentIds()).isEqualTo(plan.segmentIds());
	}

	@Test
	void aSegmentCountsOnlyWhenMoreThanHalfOfItIsCovered() {
		RoutePlan plan = roads.plan(List.of(KOLLUPITIYA, BAMBALAPITIYA));
		Double covered = jdbc.sql("""
				SELECT min(ST_Length(ST_Intersection(s.geom::geography,
				         ST_Buffer(ST_GeomFromText(:back, 4326)::geography, 5))) / s.length_m) FROM road_segment s
				WHERE s.id = ANY(:ids)""")
			.param("back", wktOf(plan.out().line(), plan.back().line()))
			.param("ids", plan.segmentIds().toArray(Long[]::new))
			.query(Double.class)
			.single();
		assertThat(covered).isGreaterThan(0.5);
	}

	@Test
	void snapsToTheNearestMainRoad() {
		RoutePlan.SnappedPoint snap = roads.snap(PETTAH_LANE);
		assertThat(snap.snapped()).isNotNull();
		assertThat(snap.offsetM()).isGreaterThan(100);
		assertThat(roads.snap(snap.snapped()).offsetM()).isLessThan(1);
	}

	@Test
	void namesRoutesFromTheNearestPlaceToEachEnd() {
		assertThat(roads.name(KOLLUPITIYA, BORELLA)).isEqualTo("Kollupitiya → Borella");
	}

	@Test
	void searchesPlaceNamesByPrefix() {
		assertThat(roads.searchPlaces("koll", 8)).extracting(Place::name).contains("Kollupitiya");
		assertThat(roads.searchPlaces("BOR", 8)).extracting(Place::name).contains("Borella");
		// From data/places-extra.csv: not in OSM, found by its name and its Sinhala name
		assertThat(roads.searchPlaces("pett", 8)).extracting(Place::name).containsExactly("Pettah");
		assertThat(roads.searchPlaces("Pitakotu", 8)).extracting(Place::name).containsExactly("Pettah");
		assertThat(roads.searchPlaces("%", 8)).isEmpty(); // LIKE wildcards are matched literally
		assertThat(roads.searchPlaces("  ", 8)).isEmpty();
	}

	@Test
	void refusesToStartWhenTheGraphIsFromAnotherExtract() {
		tx.executeWithoutResult(status -> {
			jdbc.sql("""
					INSERT INTO import_run (extract_date, source_url, finished_at)
					VALUES ('2027-01-01T00:00:00Z', 'test://newer', now())""").update();
			var props = new RoutingProperties("build/test-graph", null);
			assertThatThrownBy(() -> new Roads(props, jdbc))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("same extract");
			status.setRollbackOnly();
		});
	}

	@Test
	void refusesToStartWithoutAGraph() {
		var props = new RoutingProperties("build/no-such-graph", null);
		assertThatThrownBy(() -> new Roads(props, jdbc)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("No routing graph");
	}

	@Test
	void withoutAGraphLocationRoutingIsOff() {
		Roads off = new Roads(new RoutingProperties("", null), jdbc);
		assertThat(off.enabled()).isFalse();
		assertThatThrownBy(() -> off.plan(List.of(KOLLUPITIYA, BORELLA))).isInstanceOf(RoutingUnavailableException.class);
	}

	private static String wktOf(List<LatLon> out, List<LatLon> back) {
		Set<String> parts = new HashSet<>();
		for (List<LatLon> line : List.of(out, back)) {
			StringBuilder sb = new StringBuilder("(");
			for (int i = 0; i < line.size(); i++) {
				sb.append(i > 0 ? ", " : "").append(line.get(i).lon()).append(' ').append(line.get(i).lat());
			}
			parts.add(sb.append(')').toString());
		}
		return "MULTILINESTRING(" + String.join(", ", parts) + ")";
	}

}
