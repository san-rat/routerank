package lk.routerank.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import lk.routerank.scoring.Rankings.Link;
import lk.routerank.scoring.Rankings.Named;
import lk.routerank.scoring.Rankings.Result;
import lk.routerank.scoring.Rankings.Stretch;
import lk.routerank.scoring.StretchBuilder.Group;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Merging segments into stretches, naming and ranking them, and the files that publishes. */
class StretchesTests {

	/**
	 * A segment on an east-west road at latitude 6.9, from x0 to x1 (in hundredths of a degree east of 79.80).
	 */
	static ScoredSegment seg(long id, int x0, int x1, int points, int people, double lengthM) {
		return seg(id, x0, x1, points, people, lengthM, "Western", false, "Galle Road", "A2");
	}

	static ScoredSegment seg(long id, int x0, int x1, int points, int people, double lengthM, String province,
			boolean link, String name, String ref) {
		double lon0 = 79.80 + x0 / 100.0;
		double lon1 = 79.80 + x1 / 100.0;
		return new ScoredSegment(id, province, link, name, ref, lengthM, points, people, new int[] { people, 0, 0 },
				ScoredSegment.Node.at(lon0, 6.9),
				ScoredSegment.Node.at(lon1, 6.9), new double[][] { { lon0, 6.9 }, { lon1, 6.9 } });
	}

	static List<List<Long>> ids(List<Group> groups) {
		return groups.stream().map(g -> g.segments().stream().map(ScoredSegment::id).toList()).toList();
	}

	@Test
	void mergesNeighboursWithin20PercentOfTheRunningAverage() {
		// 20 starts first but 12 is 40% below it; then 12, 11, 10 merge, each within 20% of the average so far
		List<Group> groups = StretchBuilder.build(List.of(seg(1, 0, 1, 10, 5, 500), seg(2, 1, 2, 11, 5, 500),
				seg(3, 2, 3, 12, 5, 500), seg(4, 3, 4, 20, 6, 500)));
		assertThat(ids(groups)).containsExactlyInAnyOrder(List.of(1L, 2L, 3L), List.of(4L));
		Group merged = groups.stream().filter(g -> g.segments().size() == 3).findFirst().orElseThrow();
		assertThat(merged.points()).isEqualTo(11);
		assertThat(merged.lengthM()).isEqualTo(1500);
	}

	@Test
	void averagesAreWeightedByLength() {
		List<Group> groups = StretchBuilder.build(List.of(seg(1, 0, 1, 10, 4, 900), seg(2, 1, 2, 11, 2, 100)));
		assertThat(groups).hasSize(1);
		assertThat(groups.get(0).points()).isEqualTo(10); // (10·900 + 11·100) / 1000 = 10.1
		assertThat(groups.get(0).people()).isEqualTo(4); // 3.8
	}

	@Test
	void stopsAt10Km() {
		List<ScoredSegment> segments = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			segments.add(seg(i + 1, i, i + 1, 10, 3, 1000));
		}
		List<Group> groups = StretchBuilder.build(segments);
		assertThat(groups).extracting(Group::lengthM).containsExactlyInAnyOrder(10_000.0, 2000.0);
	}

	@Test
	void neverCrossesAProvinceBorderOrAChangeOfRoad() {
		List<Group> groups = StretchBuilder.build(List.of(seg(1, 0, 1, 10, 3, 500),
				seg(2, 1, 2, 10, 3, 500, "Sabaragamuwa", false, "Galle Road", "A2"),
				seg(3, 2, 3, 10, 3, 500, "Sabaragamuwa", false, "Ratnapura Road", "A4"),
				seg(4, 3, 4, 10, 3, 500, "Sabaragamuwa", false, "Ratnapura Road", "A8")));
		assertThat(ids(groups)).hasSize(4);
	}

	@Test
	void theRoadNumberDecidesWhenBothHaveOne() {
		// A bridge with its own name but the same number stays in the stretch
		List<Group> groups = StretchBuilder.build(List.of(seg(1, 0, 1, 10, 3, 500, "Western", false, "Kandy Road", "A1"),
				seg(2, 1, 2, 10, 3, 300, "Western", false, "Uruwel Oya Bridge", "A1"),
				seg(3, 2, 3, 10, 3, 500, "Western", false, "Kandy Road", "A1")));
		assertThat(ids(groups)).containsExactly(List.of(1L, 2L, 3L));
	}

	@Test
	void shortPiecesFoldIntoTheirNeighbour() {
		// A 40 m roundabout between two stretches of a numberless road joins the one with closer points
		List<Group> groups = StretchBuilder.build(List.of(seg(1, 0, 1, 10, 3, 500, "Western", false, "Old Road", null),
				seg(2, 1, 2, 10, 3, 40, "Western", false, "Kottawa Roundabout", null),
				seg(3, 2, 3, 30, 9, 500, "Western", false, "Old Road", null)));
		assertThat(ids(groups)).containsExactlyInAnyOrder(List.of(1L, 2L), List.of(3L));
	}

	@Test
	void bothCarriagewaysOfADualCarriagewayAreOneStretch() {
		// Two one-way carriageways that never share an end, 0–2 one way and 2–0 the other, one road in length
		ScoredSegment east1 = seg(1, 0, 1, 12, 4, 1000);
		ScoredSegment east2 = seg(2, 1, 2, 12, 4, 1000);
		ScoredSegment west1 = new ScoredSegment(3, "Western", false, "Galle Road", "A2", 1000, 11, 4, new int[] { 4, 0, 0 },
				ScoredSegment.Node.at(79.82, 6.9003), ScoredSegment.Node.at(79.81, 6.9003),
				new double[][] { { 79.82, 6.9003 }, { 79.81, 6.9003 } });
		ScoredSegment west2 = new ScoredSegment(4, "Western", false, "Galle Road", "A2", 1000, 11, 4, new int[] { 4, 0, 0 },
				ScoredSegment.Node.at(79.81, 6.9003), ScoredSegment.Node.at(79.80, 6.9003),
				new double[][] { { 79.81, 6.9003 }, { 79.80, 6.9003 } });
		List<Group> groups = StretchBuilder.build(List.of(east1, east2, west1, west2),
				List.of(new long[] { 1, 4 }, new long[] { 2, 3 }));
		assertThat(ids(groups)).containsExactly(List.of(1L, 2L, 3L, 4L));
		assertThat(groups.get(0).lengthM()).isEqualTo(2000);
	}

	@Test
	void linksFoldIntoTheClosestNeighbourAndLoneLinksStandAlone() {
		List<Group> groups = StretchBuilder.build(List.of(seg(1, 0, 1, 10, 3, 500), seg(2, 1, 2, 30, 9, 500),
				// a link at the junction of 1 and 2, closer to 2's points, then another link beyond it
				seg(3, 1, 50, 28, 8, 100, "Western", true, null, null),
				seg(4, 50, 51, 28, 8, 100, "Western", true, null, null),
				// links touching no stretch
				seg(5, 80, 81, 5, 2, 100, "Western", true, null, null)));
		assertThat(ids(groups)).containsExactlyInAnyOrder(List.of(1L), List.of(2L, 3L, 4L), List.of(5L));
		assertThat(groups.stream().filter(Group::linkOnly).flatMap(g -> g.segments().stream()))
			.extracting(ScoredSegment::id)
			.containsExactly(5L);
	}

	@Test
	void theResultDoesNotDependOnInputOrder() {
		List<ScoredSegment> segments = new ArrayList<>();
		for (int i = 0; i < 30; i++) {
			segments.add(seg(i + 1, i, i + 1, 5 + (i * 7) % 9, 3, 300 + (i * 37) % 500));
		}
		List<List<Long>> expected = ids(StretchBuilder.build(segments));
		Collections.shuffle(segments, new java.util.Random(7));
		assertThat(ids(StretchBuilder.build(segments))).isEqualTo(expected);
	}

	@Test
	void findsTheFarthestEndsToName() {
		Group g = StretchBuilder.build(List.of(seg(2, 1, 2, 10, 3, 500), seg(1, 0, 1, 10, 3, 500), seg(3, 2, 3, 10, 3, 500)))
			.get(0);
		ScoredSegment.Node[] ends = StretchBuilder.ends(g);
		assertThat(List.of(ends[0].lon(), ends[1].lon())).containsExactlyInAnyOrder(79.80, 79.83);
	}

	@Test
	void namesStretches() {
		assertThat(Naming.name("Kadawatha", "Nittambuwa", "Kandy Road")).isEqualTo("Kadawatha → Nittambuwa");
		assertThat(Naming.name("Wellawatte", "Wellawatte", "Galle Road")).isEqualTo("Galle Road, Wellawatte");
		assertThat(Naming.name("Wellawatte", "Wellawatte", null)).isEqualTo("Wellawatte");
		assertThat(Naming.name(null, null, "A4")).isEqualTo("A4");
		assertThat(Naming.slug("Kadawatha → Nittambuwa")).isEqualTo("kadawatha-nittambuwa");
		assertThat(Naming.slug("Galle Road, Wellawatte")).isEqualTo("galle-road-wellawatte");
		assertThat(Naming.slug("→")).isEqualTo("stretch");
	}

	@Test
	void namesRepeatedOnDifferentRoadsGetTheRoad() {
		Named marine = new Named(new Group("Western", List.of(seg(1, 0, 1, 9, 4, 500)), false), "Kollupitiya → Wellawatte",
				"Marine Drive", new double[2][]);
		Named galle = new Named(new Group("Western", List.of(seg(2, 5, 6, 9, 4, 500)), false), "Kollupitiya → Wellawatte",
				"Galle Road", new double[2][]);
		Named other = new Named(new Group("Western", List.of(seg(3, 9, 10, 9, 4, 500)), false), "Kadawatha → Yakkala",
				"Kandy Road", new double[2][]);
		assertThat(Naming.distinct(List.of(marine, galle, other))).extracting(Named::name)
			.containsExactly("Kollupitiya → Wellawatte via Marine Drive", "Kollupitiya → Wellawatte via Galle Road",
					"Kadawatha → Yakkala");
	}

	static Named named(String name, ScoredSegment... segments) {
		return new Named(new Group(segments[0].province(), List.of(segments),
				List.of(segments).stream().allMatch(ScoredSegment::link)), name, "Galle Road",
				new double[][] { segments[0].line()[0], segments[segments.length - 1].line()[1] });
	}

	@Test
	void ranksOnlyStretchesOf1KmWith3PeopleAndBreaksTies() {
		Result r = Rankings.rank(List.of(
				named("A → B", seg(1, 0, 1, 50, 9, 1500)),
				named("C → D", seg(2, 10, 11, 50, 9, 2500)), // same points and people, longer: ranks first
				named("E → F", seg(3, 20, 21, 50, 12, 1000)), // more people: ranks above both
				named("G → H", seg(4, 30, 31, 90, 2, 5000)), // too few people
				named("I → J", seg(5, 40, 41, 90, 9, 900)), // too short
				named("K → L", seg(6, 50, 51, 90, 9, 3000, "Southern", true, null, null))), // only a link
				List.of());
		assertThat(r.stretches()).filteredOn(Stretch::ranked)
			.extracting(Stretch::name, Stretch::rankOverall, Stretch::rankProvince)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("E → F", 1, 1),
					org.assertj.core.groups.Tuple.tuple("C → D", 2, 2),
					org.assertj.core.groups.Tuple.tuple("A → B", 3, 3));
		assertThat(r.stretches()).filteredOn(s -> !s.ranked()).hasSize(3);
	}

	@Test
	void linksKeepOpeningTheStretchThatHoldsTheirAnchor() {
		// First run: two stretches with the same name get "x-y" (busier) and "x-y-2"
		Result first = Rankings.rank(List.of(named("X → Y", seg(1, 0, 1, 10, 3, 1000)),
				named("X → Y", seg(2, 10, 11, 20, 3, 1000))), List.of());
		assertThat(first.newLinks()).containsExactly(new Link("x-y", 2), new Link("x-y-2", 1));

		// Next run: segment 1 is now in a stretch named "X → Z" with segment 3; its old link still opens it
		Result next = Rankings.rank(List.of(named("X → Z", seg(1, 0, 1, 10, 3, 1000), seg(3, 1, 2, 11, 3, 1000)),
				named("X → Y", seg(2, 10, 11, 20, 3, 1000))), first.newLinks());
		assertThat(next.newLinks()).containsExactly(new Link("x-z", 3));
		assertThat(next.slugs()).containsEntry("x-y", "x-y").containsEntry("x-y-2", "x-z").containsEntry("x-z", "x-z");
	}

	@Test
	void publishesHashedFilesAndAManifest() {
		JsonMapper json = JsonMapper.builder().build();
		Result r = Rankings.rank(List.of(named("A → B", seg(1, 0, 1, 30, 9, 1500)),
				named("C → D", seg(2, 10, 11, 12, 4, 2500))), List.of());
		Instant at = Instant.parse("2026-10-04T10:00:00Z");
		Publication p = Publication.build(r.stretches(), r.slugs(), List.of("Western", "Northern"), Map.of("Western", 13), at, json);

		assertThat(p.files().keySet()).allMatch(k -> k.matches("[a-z/0-9-]+-[0-9a-f]{12}\\.json"));
		assertThat(Publication.build(r.stretches(), r.slugs(), List.of("Western", "Northern"), Map.of("Western", 13), at, json).files().keySet())
			.isEqualTo(p.files().keySet()); // same content, same names: nothing to upload again

		JsonNode m = json.readTree(p.manifest());
		assertThat(m.get("generatedAt").asString()).isEqualTo("2026-10-04T10:00:00Z");
		assertThat(m.get("overall").get("ranked").asInt()).isEqualTo(2);
		assertThat(m.get("provinces").get("northern").get("stretches").asInt()).isZero(); // no votes yet (W08)
		assertThat(m.get("provinces").get("northern").get("heat").isNull()).isTrue();
		JsonNode western = m.get("provinces").get("western");
		assertThat(western.get("stretches").asInt()).isEqualTo(2);
		assertThat(western.get("people").asInt()).isEqualTo(13);
		String heatKey = western.get("heat").asString();
		assertThat(heatKey).startsWith("heat/western-");

		JsonNode heat = json.readTree(p.files().get(heatKey));
		assertThat(heat.get("features").get(1).get("properties").get("s").asString()).isEqualTo("a-b"); // busiest last
		JsonNode page = json.readTree(p.files().get(m.get("overall").get("pages").get(0).asString()));
		assertThat(page.get("entries").get(0).get("slug").asString()).isEqualTo("a-b");
		assertThat(page.get("entries").get(0).get("province").asString()).isEqualTo("western");
		JsonNode slugs = json.readTree(p.files().get(m.get("slugs").asString()));
		assertThat(slugs.get("c-d").get(1).asString()).isEqualTo("western");
	}

	@Test
	void colourBandsAreNationalQuantiles() {
		List<Stretch> stretches = new ArrayList<>();
		for (int p = 1; p <= 10; p++) {
			stretches.add(new Stretch("s" + p, "S", null, "Western", List.of(), 1000, p, 3, new int[3], new double[2][],
					false, null, null));
		}
		assertThat(Publication.bands(stretches)).containsExactly(3, 5, 7, 9);
		assertThat(Publication.bands(stretches.subList(0, 1))).isEmpty();
		assertThat(Publication.bands(List.of())).isEmpty();
	}

	@Test
	void provinceSlugsMatchTheSite() {
		assertThat(Publication.provinceSlug("North Western")).isEqualTo("north-western");
	}

}
