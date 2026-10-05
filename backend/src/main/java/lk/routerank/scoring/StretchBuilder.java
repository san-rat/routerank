package lk.routerank.scoring;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges neighbouring scored segments into stretches (see the Architecture doc, "The 30-minute scoring job").
 *
 * <ul>
 * <li>Each stretch grows from the highest-scoring segment not yet in one, adding neighbours whose points are
 * within 20% of the stretch's running length-weighted average, closest first, up to 10 km of road.</li>
 * <li>Neighbours share an end, or are the other carriageway of the same dual carriageway (one-way, the same road,
 * within 40 m: found by the database and passed in as {@code parallel}). Both carriageways count once in a
 * stretch's length.</li>
 * <li>A stretch never crosses a province border or a change of road: two pieces are the same road when they have
 * the same road number, or, when either has none, the same name.</li>
 * <li>Pieces under 200 m left on their own (bridges, roundabouts, short side bits) fold into a neighbouring
 * stretch on the same road where there is one, otherwise the one with the closest points. Link roads fold into
 * the neighbour with the closest points; links with no such neighbour form link-only stretches, shown on the map
 * but never ranked.</li>
 * </ul>
 * Segment points only decide what merges; a stretch's own points come from the routes that cover most of it
 * ({@link Coverage}). Segments with no points never reach the builder. The result is the same for the same input,
 * whatever its order.
 */
final class StretchBuilder {

	static final double SIMILAR = 0.20;

	static final double MAX_LENGTH_M = 10_000;

	/** Groups shorter than this fold into a neighbour, like link roads. */
	static final double FRAGMENT_M = 200;

	private StretchBuilder() {
	}

	/**
	 * A stretch before it is named and ranked.
	 *
	 * @param lengthM length of road, each dual carriageway counted once
	 * @param votes #1, #2 and #3 routes that count for it ({@link Coverage}); none until counted
	 * @param people distinct users behind those routes
	 * @param extendsBus the bus route most of its points come from extensions of ("Extends 99"), or null
	 */
	record Group(String province, List<ScoredSegment> segments, boolean linkOnly, double lengthM, int[] votes,
			int people, Long extendsBus) {

		Group(String province, List<ScoredSegment> segments, boolean linkOnly, double lengthM) {
			this(province, segments, linkOnly, lengthM, new int[3], 0, null);
		}

		/** 3 / 2 / 1 for each #1 / #2 / #3 route that counts, so the breakdown adds up exactly. */
		int points() {
			return 3 * votes[0] + 2 * votes[1] + votes[2];
		}

		Group withVotes(int[] votes, int people) {
			return withVotes(votes, people, null);
		}

		Group withVotes(int[] votes, int people, Long extendsBus) {
			return new Group(province, segments, linkOnly, lengthM, votes, people, extendsBus);
		}

		/** The segment a new stretch link is anchored to: its highest-scoring one (lowest ID on a tie). */
		ScoredSegment top() {
			return segments.stream().min(BY_POINTS).orElseThrow();
		}

		/** The road the stretch is on: the main-road segment of the road with the most length in it. */
		ScoredSegment road() {
			Map<String, Double> byRoad = new HashMap<>();
			for (ScoredSegment s : segments) {
				if (!s.link()) {
					byRoad.merge(s.ref() + "|" + s.name(), s.lengthM(), Double::sum);
				}
			}
			return segments.stream()
				.filter(s -> !s.link())
				.max(Comparator.comparingDouble((ScoredSegment s) -> byRoad.get(s.ref() + "|" + s.name()))
					.thenComparing(Comparator.comparingLong(ScoredSegment::id).reversed()))
				.orElse(segments.get(0));
		}

	}

	/** Highest points first, then the longer segment, then the lower ID, so runs are repeatable. */
	static final Comparator<ScoredSegment> BY_POINTS = Comparator.comparingInt(ScoredSegment::points)
		.reversed()
		.thenComparing(Comparator.comparingDouble(ScoredSegment::lengthM).reversed())
		.thenComparingLong(ScoredSegment::id);

	/** Same road: the same road number when both have one, otherwise the same name. */
	static boolean sameRoad(ScoredSegment a, ScoredSegment b) {
		if (a.ref() != null && b.ref() != null) {
			return a.ref().equals(b.ref());
		}
		return java.util.Objects.equals(a.name(), b.name()) && java.util.Objects.equals(a.ref(), b.ref());
	}

	static List<Group> build(List<ScoredSegment> segments) {
		return build(segments, List.of());
	}

	/**
	 * @param parallel pairs of segment IDs that are the two carriageways of one road
	 */
	static List<Group> build(List<ScoredSegment> segments, Collection<long[]> parallel) {
		Map<Long, ScoredSegment> byId = new HashMap<>();
		segments.forEach(s -> byId.put(s.id(), s));
		Map<Long, List<ScoredSegment>> byNode = new HashMap<>();
		for (ScoredSegment s : segments) {
			byNode.computeIfAbsent(s.from().key(), k -> new ArrayList<>()).add(s);
			byNode.computeIfAbsent(s.to().key(), k -> new ArrayList<>()).add(s);
		}
		Map<Long, Set<ScoredSegment>> partners = new HashMap<>();
		for (long[] pair : parallel) {
			ScoredSegment a = byId.get(pair[0]);
			ScoredSegment b = byId.get(pair[1]);
			if (a != null && b != null) {
				partners.computeIfAbsent(a.id(), k -> new LinkedHashSet<>()).add(b);
				partners.computeIfAbsent(b.id(), k -> new LinkedHashSet<>()).add(a);
			}
		}
		Graph graph = new Graph(byNode, partners);

		Map<Long, Integer> groupOf = new HashMap<>();
		List<List<ScoredSegment>> groups = new ArrayList<>();
		List<String> provinces = new ArrayList<>();

		List<ScoredSegment> seeds = segments.stream().filter(s -> !s.link()).sorted(BY_POINTS).toList();
		for (ScoredSegment seed : seeds) {
			if (groupOf.containsKey(seed.id())) {
				continue;
			}
			int g = groups.size();
			List<ScoredSegment> members = new ArrayList<>(List.of(seed));
			groupOf.put(seed.id(), g);
			double weightedPoints = seed.points() * seed.lengthM();
			double weight = seed.lengthM();
			double road = graph.roadLength(seed);
			Set<ScoredSegment> frontier = new LinkedHashSet<>(graph.neighbours(seed));
			while (true) {
				double average = weightedPoints / weight;
				ScoredSegment best = null;
				for (ScoredSegment n : frontier) {
					if (n.link() || groupOf.containsKey(n.id()) || !n.province().equals(seed.province())
							|| !sameRoad(n, seed) || Math.abs(n.points() - average) > SIMILAR * average
							|| road + graph.roadLength(n) > MAX_LENGTH_M) {
						continue;
					}
					if (best == null || closer(n, best, average)) {
						best = n;
					}
				}
				if (best == null) {
					break;
				}
				members.add(best);
				groupOf.put(best.id(), g);
				weightedPoints += best.points() * best.lengthM();
				weight += best.lengthM();
				road += graph.roadLength(best);
				frontier.remove(best);
				frontier.addAll(graph.neighbours(best));
			}
			groups.add(members);
			provinces.add(seed.province());
		}

		// Short pieces left on their own fold into a neighbour, shortest first
		boolean changed = true;
		while (changed) {
			changed = false;
			List<Integer> fragments = new ArrayList<>();
			for (int g = 0; g < groups.size(); g++) {
				if (!groups.get(g).isEmpty() && length(groups.get(g), graph) < FRAGMENT_M) {
					fragments.add(g);
				}
			}
			fragments.sort(Comparator.comparingDouble((Integer g) -> length(groups.get(g), graph)).thenComparing(g -> g));
			for (int g : fragments) {
				List<ScoredSegment> members = groups.get(g);
				if (members.isEmpty() || length(members, graph) >= FRAGMENT_M) {
					continue;
				}
				double average = average(members);
				Integer best = null;
				boolean bestSameRoad = false;
				double bestGap = Double.MAX_VALUE;
				for (ScoredSegment s : members) {
					for (ScoredSegment n : graph.neighbours(s)) {
						Integer other = groupOf.get(n.id());
						if (other == null || other == g || !provinces.get(other).equals(provinces.get(g))) {
							continue;
						}
						// A neighbour on the same road wins; then the closest points
						boolean same = !n.link() && sameRoad(s, n);
						double gap = Math.abs(average(groups.get(other)) - average);
						if (best == null || (same && !bestSameRoad)
								|| (same == bestSameRoad && (gap < bestGap || (gap == bestGap && other < best)))) {
							best = other;
							bestSameRoad = same;
							bestGap = gap;
						}
					}
				}
				if (best != null) {
					for (ScoredSegment s : members) {
						groupOf.put(s.id(), best);
					}
					groups.get(best).addAll(members);
					groups.set(g, new ArrayList<>());
					changed = true;
				}
			}
		}

		// Links fold into the neighbouring stretch with the closest points, in rounds so chains of links follow
		List<ScoredSegment> links = segments.stream().filter(ScoredSegment::link).sorted(BY_POINTS).toList();
		changed = true;
		while (changed) {
			changed = false;
			for (ScoredSegment link : links) {
				if (groupOf.containsKey(link.id())) {
					continue;
				}
				Integer best = null;
				double bestGap = Double.MAX_VALUE;
				for (ScoredSegment n : graph.neighbours(link)) {
					Integer g = groupOf.get(n.id());
					if (g == null || !provinces.get(g).equals(link.province()) || linkOnly(groups.get(g))) {
						continue;
					}
					double gap = Math.abs(link.points() - average(groups.get(g)));
					if (gap < bestGap || (gap == bestGap && g < best)) {
						best = g;
						bestGap = gap;
					}
				}
				if (best != null) {
					groups.get(best).add(link);
					groupOf.put(link.id(), best);
					changed = true;
				}
			}
		}

		// Links left over: joined links form link-only stretches
		for (ScoredSegment link : links) {
			if (groupOf.containsKey(link.id())) {
				continue;
			}
			int g = groups.size();
			List<ScoredSegment> members = new ArrayList<>();
			ArrayDeque<ScoredSegment> queue = new ArrayDeque<>(List.of(link));
			groupOf.put(link.id(), g);
			while (!queue.isEmpty()) {
				ScoredSegment s = queue.poll();
				members.add(s);
				for (ScoredSegment n : graph.neighbours(s)) {
					if (n.link() && !groupOf.containsKey(n.id()) && n.province().equals(link.province())) {
						groupOf.put(n.id(), g);
						queue.add(n);
					}
				}
			}
			groups.add(members);
			provinces.add(link.province());
		}

		List<Group> result = new ArrayList<>();
		for (int g = 0; g < groups.size(); g++) {
			if (groups.get(g).isEmpty()) {
				continue;
			}
			List<ScoredSegment> members = new ArrayList<>(groups.get(g));
			members.sort(Comparator.comparingLong(ScoredSegment::id));
			result.add(new Group(provinces.get(g), List.copyOf(members), linkOnly(members), length(members, graph)));
		}
		return result;
	}

	/** Who touches whom: segments sharing an end, and the two carriageways of a dual carriageway. */
	private record Graph(Map<Long, List<ScoredSegment>> byNode, Map<Long, Set<ScoredSegment>> partners) {

		Set<ScoredSegment> neighbours(ScoredSegment s) {
			Set<ScoredSegment> found = new LinkedHashSet<>();
			for (ScoredSegment n : byNode.getOrDefault(s.from().key(), List.of())) {
				if (n.id() != s.id()) {
					found.add(n);
				}
			}
			for (ScoredSegment n : byNode.getOrDefault(s.to().key(), List.of())) {
				if (n.id() != s.id()) {
					found.add(n);
				}
			}
			found.addAll(partners.getOrDefault(s.id(), Set.of()));
			return found;
		}

		/** A carriageway with a partner counts half: the two together are one length of road. */
		double roadLength(ScoredSegment s) {
			return partners.containsKey(s.id()) ? s.lengthM() / 2 : s.lengthM();
		}

	}

	private static double length(List<ScoredSegment> members, Graph graph) {
		return members.stream().mapToDouble(graph::roadLength).sum();
	}

	private static boolean linkOnly(List<ScoredSegment> members) {
		return members.stream().allMatch(ScoredSegment::link);
	}

	private static double average(List<ScoredSegment> members) {
		double sum = 0;
		double length = 0;
		for (ScoredSegment s : members) {
			sum += s.points() * s.lengthM();
			length += s.lengthM();
		}
		return sum / length;
	}

	/** Closer to the average wins; then the usual order (points, length, ID), so the choice is repeatable. */
	private static boolean closer(ScoredSegment a, ScoredSegment b, double average) {
		double gapA = Math.abs(a.points() - average);
		double gapB = Math.abs(b.points() - average);
		return gapA != gapB ? gapA < gapB : BY_POINTS.compare(a, b) < 0;
	}

	/** The two ends of a stretch that are farthest apart, used to name it. */
	static ScoredSegment.Node[] ends(Group group) {
		Map<Long, Integer> degree = new HashMap<>();
		Map<Long, ScoredSegment.Node> nodes = new HashMap<>();
		for (ScoredSegment s : group.segments()) {
			degree.merge(s.from().key(), 1, Integer::sum);
			degree.merge(s.to().key(), 1, Integer::sum);
			nodes.putIfAbsent(s.from().key(), s.from());
			nodes.putIfAbsent(s.to().key(), s.to());
		}
		List<ScoredSegment.Node> candidates = nodes.values()
			.stream()
			.filter(n -> degree.get(n.key()) == 1)
			.sorted(Comparator.comparingLong(ScoredSegment.Node::key))
			.toList();
		if (candidates.size() < 2) { // a loop: any two points
			candidates = nodes.values().stream().sorted(Comparator.comparingLong(ScoredSegment.Node::key)).toList();
		}
		ScoredSegment.Node[] best = { candidates.get(0), candidates.get(candidates.size() - 1) };
		double bestDistance = -1;
		for (int i = 0; i < candidates.size(); i++) {
			for (int j = i + 1; j < candidates.size(); j++) {
				double d = distanceM(candidates.get(i), candidates.get(j));
				if (d > bestDistance) {
					bestDistance = d;
					best = new ScoredSegment.Node[] { candidates.get(i), candidates.get(j) };
				}
			}
		}
		return best;
	}

	/** Equirectangular distance: plenty for comparing points a few kilometres apart. */
	static double distanceM(ScoredSegment.Node a, ScoredSegment.Node b) {
		double x = Math.toRadians(b.lon() - a.lon()) * Math.cos(Math.toRadians((a.lat() + b.lat()) / 2));
		double y = Math.toRadians(b.lat() - a.lat());
		return Math.sqrt(x * x + y * y) * 6_371_000;
	}

	static Set<Long> ids(Group group) {
		Set<Long> ids = new HashSet<>();
		group.segments().forEach(s -> ids.add(s.id()));
		return ids;
	}

}
