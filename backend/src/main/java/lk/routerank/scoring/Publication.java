package lk.routerank.scoring;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonInclude;
import lk.routerank.scoring.Rankings.Stretch;
import tools.jackson.databind.json.JsonMapper;

/**
 * The files one run publishes. Every file but the manifest is named by a hash of its content
 * ({@code heat/western-3f9a…json}), so an unchanged file keeps its name and is not uploaded again, and browsers
 * can cache it forever. The manifest names the current files and is written last.
 *
 * <ul>
 * <li>{@code heat/<province>-<hash>.json}: GeoJSON, one feature per stretch ({@code s} slug, {@code p} points),
 * busiest last so they draw on top</li>
 * <li>{@code details/<province>-<hash>.json}: every stretch in the province by slug, and its whole roads</li>
 * <li>{@code leaderboard/<overall|province>-<page>-<hash>.json}: ranked stretches, 20 a page</li>
 * <li>{@code slugs-<hash>.json}: every link slug to its stretch's own slug and province</li>
 * <li>{@code buses-<hash>.json}: the active bus routes, each with its most-wanted extension</li>
 * <li>{@code manifest.json}: the above, the colour bands and when the run was</li>
 * </ul>
 */
final class Publication {

	static final int PAGE_SIZE = 20;

	static final String MANIFEST = "manifest.json";

	/** Quantiles of stretch points that separate the five heatmap colours. */
	static final double[] BAND_QUANTILES = { 0.2, 0.4, 0.6, 0.8 };

	private final Map<String, byte[]> files = new LinkedHashMap<>();

	private final byte[] manifest;

	private Publication(Map<String, byte[]> files, byte[] manifest) {
		this.files.putAll(files);
		this.manifest = manifest;
	}

	/** Hashed files by key, in a stable order. */
	Map<String, byte[]> files() {
		return files;
	}

	byte[] manifest() {
		return manifest;
	}

	// The JSON shapes the site reads (frontend/src/rankings/data.ts)

	record Feature(String type, Map<String, Object> properties, Map<String, Object> geometry) {
	}

	record FeatureCollection(String type, List<Feature> features) {
	}

	/**
	 * @param road the road's name or number, or null
	 * @param votes #1, #2 and #3 votes ("Where the points come from")
	 * @param ends its two ends as {@code [lon, lat]} ("Vote for this stretch" starts a route between them)
	 * @param bbox {@code [west, south, east, north]}
	 * @param extendsBus the number of the bus route most of its points come from extensions of ("Extends 99")
	 * @param along the whole road it is part of, a key in {@link Details#roads}, or null
	 */
	record Detail(String name, String road, String province, int points, int people, int[] votes, long lengthM,
			Integer rankOverall, Integer rankProvince, double[][] ends, double[] bbox,
			@JsonInclude(JsonInclude.Include.NON_NULL) String extendsBus,
			@JsonInclude(JsonInclude.Include.NON_NULL) String along) {
	}

	/**
	 * A whole road ({@link Roads}): two or more stretches joined end to end with the same road name. Display only.
	 *
	 * @param stretches their slugs, from the end nearer Colombo
	 * @param lengthM their lengths added up
	 */
	record Road(String name, long lengthM, List<String> stretches, double[] bbox) {
	}

	/** @param roads whole roads by key, the slug of their first stretch */
	record Details(String province, Map<String, Detail> stretches, Map<String, Road> roads) {
	}

	record Entry(int rank, String slug, String name, String province, int points, int people, long lengthM,
			double[] bbox, @JsonInclude(JsonInclude.Include.NON_NULL) String extendsBus) {
	}

	/**
	 * A bus route on the map (W05).
	 *
	 * @param out its way from start to end, {@code [lon, lat]} pairs
	 * @param back its way back
	 * @param backLeaves the parts of the way back that leave the way there, drawn dashed; none when it comes back
	 * the same way
	 * @param backVia the roads those parts use ("Duplication Road"); empty for bus routes drawn before it was kept
	 * @param towns towns it passes, in order
	 * @param wanted its most-wanted extension: the stretch with the most points from extensions of it, or null
	 */
	record Bus(String number, String name, String startName, String endName, double[] start, double[] end,
			List<String> towns, long lengthOutM, long lengthBackM, double[][] out, double[][] back,
			double[][][] backLeaves, List<String> backVia, double[] bbox, Wanted wanted) {
	}

	/** A bus route's most-wanted extension, as its sheet shows it ("Busiest stretch #6 in Western Province"). */
	record Wanted(String slug, String name, String province, int points, Integer rankProvince) {
	}

	record Buses(List<Bus> routes) {
	}

	record Page(int page, List<Entry> entries) {
	}

	/**
	 * @param stretches every stretch in the province, ranked or not; 0 means no votes yet (W08)
	 * @param people distinct voters whose routes score in the province
	 * @param heat null when there are no stretches
	 * @param details null when there are no stretches
	 */
	record ProvinceEntry(String name, int stretches, int ranked, int people, String heat, String details,
			List<String> pages) {
	}

	record Overall(int ranked, List<String> pages) {
	}

	/**
	 * @param generatedAt when the run read the votes; the site shows it as "Updated …"
	 * @param bands points thresholds between the heatmap's five colours, ascending (fewer when scores are few)
	 * @param buses the bus routes file
	 */
	record Manifest(int version, Instant generatedAt, int pageSize, List<Integer> bands, Overall overall,
			Map<String, ProvinceEntry> provinces, String slugs, String buses) {
	}

	static String provinceSlug(String province) {
		return province.toLowerCase(Locale.ROOT).replace(' ', '-');
	}

	/**
	 * @param buses the active bus routes, without their most-wanted extensions (filled in here)
	 * @param busNumbers every bus route's number by ID, retired ones too (their extensions still count)
	 */
	static Publication build(List<Stretch> stretches, Map<String, String> slugs, List<String> provinces,
			Map<String, Integer> people, List<BusLine> buses, Map<Long, String> busNumbers, Instant generatedAt,
			JsonMapper json) {
		Map<String, byte[]> files = new LinkedHashMap<>();
		Map<String, ProvinceEntry> provinceEntries = new TreeMap<>();

		List<Stretch> ranked = stretches.stream()
			.filter(Stretch::ranked)
			.sorted(Comparator.comparing(Stretch::rankOverall))
			.toList();
		List<String> overallPages = pages("overall", ranked, true, busNumbers, files, json);

		Map<String, String> slugProvince = new LinkedHashMap<>();
		Map<String, String> along = new LinkedHashMap<>();
		Map<String, Map<String, Road>> roadsByProvince = new LinkedHashMap<>();
		for (List<Stretch> road : Roads.group(stretches)) {
			String key = road.get(0).slug();
			road.forEach(s -> along.put(s.slug(), key));
			roadsByProvince.computeIfAbsent(road.get(0).province(), k -> new LinkedHashMap<>())
				.put(key, new Road(road.get(0).road(), Math.round(road.stream().mapToDouble(Stretch::lengthM).sum()),
						road.stream().map(Stretch::slug).toList(),
						bbox(road.stream().flatMap(s -> s.segments().stream()).map(ScoredSegment::line)
							.toArray(double[][][]::new))));
		}
		for (String province : provinces) {
			String slug = provinceSlug(province);
			List<Stretch> here = stretches.stream().filter(s -> s.province().equals(province)).toList();
			here.forEach(s -> slugProvince.put(s.slug(), slug));
			if (here.isEmpty()) {
				provinceEntries.put(slug, new ProvinceEntry(province, 0, 0, 0, null, null, List.of()));
				continue;
			}
			List<Feature> features = new ArrayList<>();
			Map<String, Detail> details = new LinkedHashMap<>();
			here.stream()
				.sorted(Comparator.comparingInt(Stretch::points).thenComparing(Stretch::slug))
				.forEach(s -> features.add(new Feature("Feature", ordered("s", s.slug(), "p", s.points()),
						ordered("type", "MultiLineString", "coordinates",
								s.segments().stream().map(ScoredSegment::line).toList()))));
			here.stream()
				.sorted(Comparator.comparing(Stretch::slug))
				.forEach(s -> details.put(s.slug(), new Detail(s.name(), s.road(), province, s.points(), s.people(),
						s.votes(), Math.round(s.lengthM()), s.rankOverall(), s.rankProvince(), s.ends(), bbox(s),
						number(s, busNumbers), along.get(s.slug()))));
			String heat = put(files, "heat/" + slug, json.writeValueAsBytes(new FeatureCollection("FeatureCollection",
					features)));
			String detailsKey = put(files, "details/" + slug, json.writeValueAsBytes(new Details(province, details,
					roadsByProvince.getOrDefault(province, Map.of()))));
			List<Stretch> rankedHere = here.stream()
				.filter(Stretch::ranked)
				.sorted(Comparator.comparing(Stretch::rankProvince))
				.toList();
			provinceEntries.put(slug, new ProvinceEntry(province, here.size(), rankedHere.size(),
					people.getOrDefault(province, 0), heat, detailsKey,
					pages(slug, rankedHere, false, busNumbers, files, json)));
		}

		Map<String, List<String>> slugIndex = new TreeMap<>();
		slugs.forEach((link, own) -> {
			String province = slugProvince.get(own);
			if (province != null) {
				slugIndex.put(link, List.of(own, province));
			}
		});
		String slugsKey = put(files, "slugs", json.writeValueAsBytes(slugIndex));

		List<Bus> busFile = buses.stream().map(b -> {
			Wanted wanted = stretches.stream()
				.filter(s -> Long.valueOf(b.id()).equals(s.extendsBus()))
				.max(Comparator.comparingInt(Stretch::points).thenComparing(Stretch::slug, Comparator.reverseOrder()))
				.map(s -> new Wanted(s.slug(), s.name(), provinceSlug(s.province()), s.points(), s.rankProvince()))
				.orElse(null);
			return new Bus(b.number(), b.startName() + " → " + b.endName(), b.startName(), b.endName(), b.start(), b.end(),
					b.towns(), Math.round(b.lengthOutM()), Math.round(b.lengthBackM()), b.out(), b.back(), b.backLeaves(),
					b.backVia(), bbox(b.out(), b.back()), wanted);
		}).toList();
		String busesKey = put(files, "buses", json.writeValueAsBytes(new Buses(busFile)));

		Manifest m = new Manifest(1, generatedAt, PAGE_SIZE, bands(stretches), new Overall(ranked.size(), overallPages),
				provinceEntries, slugsKey, busesKey);
		return new Publication(files, json.writeValueAsBytes(m));
	}

	private static List<String> pages(String name, List<Stretch> ranked, boolean overall, Map<Long, String> busNumbers,
			Map<String, byte[]> files, JsonMapper json) {
		List<String> keys = new ArrayList<>();
		for (int from = 0, page = 1; from < ranked.size(); from += PAGE_SIZE, page++) {
			List<Entry> entries = ranked.subList(from, Math.min(from + PAGE_SIZE, ranked.size()))
				.stream()
				.map(s -> new Entry(overall ? s.rankOverall() : s.rankProvince(), s.slug(), s.name(),
						provinceSlug(s.province()), s.points(), s.people(), Math.round(s.lengthM()), bbox(s),
						number(s, busNumbers)))
				.toList();
			keys.add(put(files, "leaderboard/" + name + "-" + page, json.writeValueAsBytes(new Page(page, entries))));
		}
		return keys;
	}

	/** Thresholds between the five colours: quantiles of stretch points across the whole country. */
	static List<Integer> bands(List<Stretch> stretches) {
		int[] points = stretches.stream().mapToInt(Stretch::points).sorted().toArray();
		List<Integer> bands = new ArrayList<>();
		if (points.length == 0) {
			return bands;
		}
		for (double q : BAND_QUANTILES) {
			int value = points[(int) Math.floor(q * points.length)];
			if (value > points[0] && (bands.isEmpty() || value > bands.get(bands.size() - 1))) {
				bands.add(value);
			}
		}
		return bands;
	}

	private static String number(Stretch s, Map<Long, String> busNumbers) {
		return s.extendsBus() == null ? null : busNumbers.get(s.extendsBus());
	}

	private static double[] bbox(Stretch s) {
		return bbox(s.segments().stream().map(ScoredSegment::line).toArray(double[][][]::new));
	}

	private static double[] bbox(double[][]... lines) {
		double west = Double.MAX_VALUE, south = Double.MAX_VALUE, east = -Double.MAX_VALUE, north = -Double.MAX_VALUE;
		for (double[][] line : lines) {
			for (double[] p : line) {
				west = Math.min(west, p[0]);
				east = Math.max(east, p[0]);
				south = Math.min(south, p[1]);
				north = Math.max(north, p[1]);
			}
		}
		return new double[] { west, south, east, north };
	}

	/**
	 * An active bus route as the scoring job reads it.
	 *
	 * @param start {@code [lon, lat]}
	 */
	record BusLine(long id, String number, String startName, String endName, double[] start, double[] end,
			List<String> towns, double lengthOutM, double lengthBackM, double[][] out, double[][] back,
			double[][][] backLeaves, List<String> backVia) {
	}

	private static String put(Map<String, byte[]> files, String name, byte[] body) {
		String key = name + "-" + SigV4.sha256Hex(body).substring(0, 12) + ".json";
		files.put(key, body);
		return key;
	}

	private static Map<String, Object> ordered(String k1, Object v1, String k2, Object v2) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put(k1, v1);
		map.put(k2, v2);
		return map;
	}

}
