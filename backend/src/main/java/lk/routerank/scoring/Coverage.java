package lk.routerank.scoring;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lk.routerank.scoring.StretchBuilder.Group;

/**
 * Who counts for each stretch (see the Spec, "Stretch points"): a route counts when it covers more than half of
 * the stretch's length. A stretch's points are then 3 / 2 / 1 for each counted #1 / #2 / #3 route, and its people
 * the distinct users behind those routes, so "Where the points come from" adds up exactly.
 *
 * <p>
 * Lengths are as the stretch measures them: the two carriageways of a dual carriageway are one length of road,
 * so a route on either carriageway covers that piece of road, and a round trip using both counts once.
 */
final class Coverage {

	/**
	 * A counted route: one vote, from one user, in one slot (1, 2 or 3).
	 *
	 * @param segmentIds the segments it scores on (an extension's new part only)
	 * @param extendsBus for an extension, the bus route it extends; null for a new route
	 */
	record Route(long userId, int slot, long[] segmentIds, Long extendsBus) {

		Route(long userId, int slot, long[] segmentIds) {
			this(userId, slot, segmentIds, null);
		}

	}

	/** A route counts for a stretch when it covers more than this share of it. */
	static final double MORE_THAN = 0.5;

	private final List<Group> groups;

	private final Map<Long, Integer> groupOf = new HashMap<>();

	private final Map<Long, Double> roadLength = new HashMap<>();

	private final Map<Long, List<Long>> partners = new HashMap<>();

	private final int[][] votes;

	private final List<Set<Long>> users = new ArrayList<>();

	/** Per stretch, the points that came from extensions of each bus route. */
	private final List<Map<Long, Integer>> extensionPoints = new ArrayList<>();

	/**
	 * @param parallel the pairs of carriageways the stretches were built with
	 */
	Coverage(List<Group> groups, Collection<long[]> parallel) {
		this.groups = groups;
		this.votes = new int[groups.size()][3];
		for (int g = 0; g < groups.size(); g++) {
			for (ScoredSegment s : groups.get(g).segments()) {
				groupOf.put(s.id(), g);
				roadLength.put(s.id(), s.lengthM());
			}
			users.add(new HashSet<>());
			extensionPoints.add(new HashMap<>());
		}
		for (long[] pair : parallel) {
			if (groupOf.containsKey(pair[0]) && groupOf.containsKey(pair[1])) {
				partners.computeIfAbsent(pair[0], k -> new ArrayList<>()).add(pair[1]);
				partners.computeIfAbsent(pair[1], k -> new ArrayList<>()).add(pair[0]);
			}
		}
		// As StretchBuilder measures: a carriageway with a partner is half a length of road
		partners.keySet().forEach(id -> roadLength.compute(id, (k, length) -> length / 2));
	}

	void add(Route route) {
		Set<Long> covered = new HashSet<>();
		for (long id : route.segmentIds()) {
			if (groupOf.containsKey(id)) {
				covered.add(id);
				covered.addAll(partners.getOrDefault(id, List.of()));
			}
		}
		Map<Integer, Double> lengths = new HashMap<>();
		for (long id : covered) {
			lengths.merge(groupOf.get(id), roadLength.get(id), Double::sum);
		}
		lengths.forEach((g, length) -> {
			// The margin keeps exactly half from counting through rounding
			if (length > groups.get(g).lengthM() * MORE_THAN + 1e-6) {
				votes[g][route.slot() - 1]++;
				users.get(g).add(route.userId());
				if (route.extendsBus() != null) {
					extensionPoints.get(g).merge(route.extendsBus(), 4 - route.slot(), Integer::sum);
				}
			}
		});
	}

	/**
	 * The stretches with their counted votes and people, each tagged with the bus route more than half of its
	 * points come from extensions of, if any ("Extends 99").
	 */
	List<Group> counted() {
		List<Group> counted = new ArrayList<>();
		for (int g = 0; g < groups.size(); g++) {
			int points = 3 * votes[g][0] + 2 * votes[g][1] + votes[g][2];
			Long extendsBus = extensionPoints.get(g)
				.entrySet()
				.stream()
				.filter(e -> e.getValue() * 2 > points)
				.map(Map.Entry::getKey)
				.findFirst()
				.orElse(null);
			counted.add(groups.get(g).withVotes(votes[g].clone(), users.get(g).size(), extendsBus));
		}
		return counted;
	}

}
