package lk.routerank.scoring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lk.routerank.scoring.StretchBuilder.Group;

/**
 * Turns named groups into ranked stretches with stable links. Pure: the scoring job loads the inputs and
 * stores the result.
 */
final class Rankings {

	/** Leaderboards list only stretches at least this long... */
	static final double MIN_RANKED_M = 1_000;

	/** ...with at least this many people behind them. The heatmap shows every stretch. */
	static final int MIN_RANKED_PEOPLE = 3;

	private Rankings() {
	}

	/**
	 * A stretch as published.
	 *
	 * @param slug its link, {@code /s/<slug>}
	 * @param road the road's name or number, or null
	 * @param points 3 / 2 / 1 for each #1 / #2 / #3 route covering more than half of it
	 * @param people distinct users behind those routes
	 * @param votes how many of those routes are #1, #2 and #3
	 * @param ends its two ends as {@code [lon, lat]}, the one nearer Colombo first
	 * @param rankOverall null when not on the leaderboards
	 * @param rankProvince null when not on the leaderboards
	 */
	record Stretch(String slug, String name, String road, String province, List<ScoredSegment> segments, double lengthM,
			int points, int people, int[] votes, double[][] ends, boolean linkOnly, Integer rankOverall,
			Integer rankProvince) {

		boolean ranked() {
			return rankOverall != null;
		}

		List<Long> segmentIds() {
			return segments.stream().map(ScoredSegment::id).toList();
		}

	}

	/** An existing link: a slug and the segment it opens. */
	record Link(String slug, long anchorSegmentId) {
	}

	/** A named group, before slugs and ranks; {@code ends} as in {@link Stretch}. */
	record Named(Group group, String name, String road, double[][] ends) {
	}

	/**
	 * @param stretches every stretch, ranked ones first in rank order
	 * @param newLinks links made this run, to store
	 * @param slugs every slug that opens a current stretch (old ones included), to the stretch's own slug
	 */
	record Result(List<Stretch> stretches, List<Link> newLinks, Map<String, String> slugs) {
	}

	/** Ranking order: points, then people, then length (longer first), then name; the slug settles exact ties. */
	static final Comparator<Stretch> ORDER = Comparator.comparingInt(Stretch::points)
		.reversed()
		.thenComparing(Comparator.comparingInt(Stretch::people).reversed())
		.thenComparing(Comparator.comparingDouble(Stretch::lengthM).reversed())
		.thenComparing(Stretch::name)
		.thenComparing(Stretch::slug);

	/**
	 * @param named this run's stretches with their names
	 * @param links existing links, oldest first
	 */
	static Result rank(List<Named> named, List<Link> links) {
		Map<Long, List<String>> slugsByAnchor = new HashMap<>();
		Set<String> taken = new HashSet<>();
		for (Link link : links) {
			slugsByAnchor.computeIfAbsent(link.anchorSegmentId(), k -> new ArrayList<>()).add(link.slug());
			taken.add(link.slug());
		}

		// Busiest first, so when names collide the busier stretch keeps the plain slug
		List<Named> ordered = new ArrayList<>(named);
		ordered.sort(Comparator.comparing((Named n) -> n.group().points()).reversed()
			.thenComparing(n -> n.group().top().id()));

		List<Link> newLinks = new ArrayList<>();
		Map<String, String> slugs = new LinkedHashMap<>();
		List<Stretch> unranked = new ArrayList<>();
		for (Named n : ordered) {
			List<String> existing = new ArrayList<>();
			for (ScoredSegment s : n.group().segments()) {
				existing.addAll(slugsByAnchor.getOrDefault(s.id(), List.of()));
			}
			String base = Naming.slug(n.name());
			// Keep a link already made for this name; otherwise make one, so the address matches the name
			String own = existing.stream().filter(slug -> matches(slug, base)).findFirst().orElse(null);
			if (own == null) {
				own = base;
				for (int i = 2; taken.contains(own); i++) {
					own = base + "-" + i;
				}
				taken.add(own);
				newLinks.add(new Link(own, n.group().top().id()));
			}
			slugs.put(own, own);
			for (String old : existing) {
				slugs.put(old, own);
			}
			Group g = n.group();
			unranked.add(new Stretch(own, n.name(), n.road(), g.province(), g.segments(), g.lengthM(), g.points(),
					g.people(), g.votes(), n.ends(), g.linkOnly(), null, null));
		}

		List<Stretch> eligible = unranked.stream().filter(Rankings::eligible).sorted(ORDER).toList();
		Map<String, Integer> provinceRanks = new HashMap<>();
		List<Stretch> stretches = new ArrayList<>();
		for (int i = 0; i < eligible.size(); i++) {
			Stretch s = eligible.get(i);
			int inProvince = provinceRanks.merge(s.province(), 1, Integer::sum);
			stretches.add(new Stretch(s.slug(), s.name(), s.road(), s.province(), s.segments(), s.lengthM(), s.points(),
					s.people(), s.votes(), s.ends(), s.linkOnly(), i + 1, inProvince));
		}
		unranked.stream().filter(s -> !eligible(s)).sorted(ORDER).forEach(stretches::add);
		return new Result(List.copyOf(stretches), List.copyOf(newLinks), slugs);
	}

	static boolean eligible(Stretch s) {
		return !s.linkOnly() && s.lengthM() >= MIN_RANKED_M && s.people() >= MIN_RANKED_PEOPLE;
	}

	/** "kadawatha-nittambuwa" or a numbered duplicate of it ("kadawatha-nittambuwa-2"). */
	private static boolean matches(String slug, String base) {
		return slug.equals(base) || (slug.startsWith(base + "-") && slug.substring(base.length() + 1).matches("\\d+"));
	}

}
