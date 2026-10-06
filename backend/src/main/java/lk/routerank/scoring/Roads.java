package lk.routerank.scoring;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import lk.routerank.scoring.Rankings.Stretch;

/**
 * Whole roads, for display only (see the Spec, "Named stretches"): stretches that share an end and the same road
 * name, in the same province. A stretch ends where the road number changes or at 10 km, so one road someone drives
 * along ("Avissawella Road", numbered B435 then AB10) can be several stretches; the map shows them together.
 * Scores and ranks are untouched. Pure.
 */
final class Roads {

	private Roads() {
	}

	/**
	 * Roads of two or more stretches, each ordered from the end nearer Colombo, the busiest road first. Link-only
	 * stretches and stretches with no road name are never part of one.
	 */
	static List<List<Stretch>> group(List<Stretch> stretches) {
		List<Stretch> candidates = stretches.stream().filter(s -> !s.linkOnly() && s.road() != null).toList();
		// Stretches that share a segment end
		Map<Long, List<Integer>> byNode = new HashMap<>();
		for (int i = 0; i < candidates.size(); i++) {
			Set<Long> nodes = new HashSet<>();
			for (ScoredSegment s : candidates.get(i).segments()) {
				nodes.add(s.from().key());
				nodes.add(s.to().key());
			}
			for (long node : nodes) {
				byNode.computeIfAbsent(node, k -> new ArrayList<>()).add(i);
			}
		}
		Map<Integer, Set<Integer>> neighbours = new HashMap<>();
		for (List<Integer> here : byNode.values()) {
			for (int a : here) {
				for (int b : here) {
					if (a != b && sameRoad(candidates.get(a), candidates.get(b))) {
						neighbours.computeIfAbsent(a, k -> new HashSet<>()).add(b);
					}
				}
			}
		}

		List<List<Stretch>> roads = new ArrayList<>();
		boolean[] seen = new boolean[candidates.size()];
		for (int start = 0; start < candidates.size(); start++) {
			if (seen[start] || !neighbours.containsKey(start)) {
				continue;
			}
			List<Stretch> road = new ArrayList<>();
			ArrayDeque<Integer> queue = new ArrayDeque<>(List.of(start));
			seen[start] = true;
			while (!queue.isEmpty()) {
				int i = queue.poll();
				road.add(candidates.get(i));
				for (int n : neighbours.getOrDefault(i, Set.of())) {
					if (!seen[n]) {
						seen[n] = true;
						queue.add(n);
					}
				}
			}
			road.sort(Comparator.comparingDouble(Roads::fromColombo).thenComparing(Stretch::slug));
			roads.add(List.copyOf(road));
		}
		roads.sort(Comparator.comparingInt((List<Stretch> r) -> r.stream().mapToInt(Stretch::points).max().orElse(0))
			.reversed()
			.thenComparing(r -> r.get(0).slug()));
		return roads;
	}

	private static boolean sameRoad(Stretch a, Stretch b) {
		return a.province().equals(b.province()) && Objects.equals(a.road(), b.road());
	}

	/** How far its end nearer Colombo is from Colombo. */
	private static double fromColombo(Stretch s) {
		double[] near = s.ends()[0];
		return StretchBuilder.distanceM(ScoredSegment.Node.at(near[0], near[1]), Naming.COLOMBO);
	}

}
