package lk.routerank.scoring;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Stretch names and the slugs in their links. */
final class Naming {

	/** Colombo Fort: of a stretch's two ends, the one nearer here comes first ("Kadawatha → Nittambuwa"). */
	static final ScoredSegment.Node COLOMBO = ScoredSegment.Node.at(79.8428, 6.9344);

	private Naming() {
	}

	/**
	 * "Kadawatha → Nittambuwa" from the nearest places to the two ends, the end nearer Colombo first; when both
	 * ends have the same nearest place, the road and the place ("Galle Road, Wellawatte").
	 *
	 * @param near the nearer end's place, or null when there is none
	 * @param far the other end's place, or null
	 * @param road the road's name or number, or null
	 */
	static String name(String near, String far, String road) {
		if (near != null && far != null && !near.equals(far)) {
			return near + " → " + far;
		}
		String place = near != null ? near : far;
		if (road == null) {
			return place != null ? place : "Main road";
		}
		return place != null ? road + ", " + place : road;
	}

	/**
	 * Two roads between the same towns ("Kollupitiya → Wellawatte" along Marine Drive and along Galle Road) would
	 * share a name: in a province, stretches whose names repeat on different roads get the road added ("Kollupitiya
	 * → Wellawatte via Marine Drive").
	 */
	static List<Rankings.Named> distinct(List<Rankings.Named> named) {
		Map<String, Set<String>> roads = new HashMap<>();
		for (Rankings.Named n : named) {
			roads.computeIfAbsent(n.group().province() + "|" + n.name(), k -> new HashSet<>()).add(String.valueOf(n.road()));
		}
		List<Rankings.Named> result = new ArrayList<>();
		for (Rankings.Named n : named) {
			boolean clash = roads.get(n.group().province() + "|" + n.name()).size() > 1;
			result.add(clash && n.road() != null && n.name().contains(" → ")
					? new Rankings.Named(n.group(), n.name() + " via " + n.road(), n.road(), n.ends())
					: n);
		}
		return result;
	}

	/** The road a stretch is on, for its name and details: its name, else its number. */
	static String road(String name, String ref) {
		return name != null ? name : ref;
	}

	/** "Kadawatha → Nittambuwa" becomes "kadawatha-nittambuwa". */
	static String slug(String name) {
		String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
		return slug.isEmpty() ? "stretch" : slug;
	}

}
