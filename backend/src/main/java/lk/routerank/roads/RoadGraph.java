package lk.routerank.roads;

import static com.graphhopper.json.Statement.If;
import static com.graphhopper.json.Statement.Op.LIMIT;
import static com.graphhopper.json.Statement.Op.MULTIPLY;

import java.time.Instant;
import java.time.OffsetDateTime;

import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.config.Profile;
import com.graphhopper.util.CustomModel;

/**
 * The GraphHopper setup shared by the API (which loads a graph) and {@link BuildGraph} (which builds one): main
 * roads only (trunk, primary, secondary and their links), following one-way tags (car access), with OSM way IDs
 * kept so paths map onto road segments (ADR 0001). No turn costs and no speed-up preparation: the island's
 * main-road graph is small enough for plain Dijkstra-style routing.
 */
final class RoadGraph {

	static final String PROFILE = "main_roads";

	/** GraphHopper keeps the extract's replication timestamp from the .osm.pbf header under this key. */
	static final String EXTRACT_DATE = "datareader.data.date";

	private RoadGraph() {
	}

	/**
	 * A configured, not yet loaded GraphHopper.
	 * @param osmFile the extract to build from, or {@code null} to only load an existing graph
	 * @param readOnly memory-map the graph read-only (the API); building needs {@code false}
	 */
	static GraphHopper create(String location, String osmFile, boolean readOnly) {
		GraphHopperConfig config = new GraphHopperConfig();
		config.putObject("graph.location", location);
		if (osmFile != null) {
			config.putObject("datareader.file", osmFile);
		}
		// Memory-mapped, so the graph sits in the OS page cache rather than the 512 MB Java heap
		config.putObject("graph.dataaccess.default_type", readOnly ? "MMAP_RO" : "MMAP");
		config.putObject("graph.encoded_values", "car_access, car_average_speed, road_access, road_class, road_class_link, osm_way_id");
		// The input is already filtered to main roads; skipping the rest also keeps a full extract's graph small
		config.putObject("import.osm.ignored_highways", "motorway,motorway_link,tertiary,tertiary_link,unclassified,"
				+ "residential,living_street,service,track,road,footway,cycleway,path,steps,pedestrian,bridleway,construction");
		// Waypoints can be up to the 40 km cap apart (plus room for detours)
		config.putObject("routing.non_ch.max_waypoint_distance", 100_000);
		config.setProfiles(java.util.List.of(new Profile(PROFILE).setCustomModel(mainRoads())));

		GraphHopper hopper = new GraphHopper();
		hopper.init(config);
		return hopper;
	}

	/** GraphHopper's car model (car.json) plus "main roads only" (the Phase 1 spike's main_roads.json). */
	static CustomModel mainRoads() {
		return new CustomModel()
			.setDistanceInfluence(90d)
			.addToPriority(If("!car_access", MULTIPLY, "0"))
			.addToPriority(If("road_class != TRUNK && road_class != PRIMARY && road_class != SECONDARY", MULTIPLY, "0"))
			.addToSpeed(If("true", LIMIT, "car_average_speed"));
	}

	/** The extract date stamped into a loaded graph, or {@code null} if the extract had none. */
	static Instant extractDate(GraphHopper hopper) {
		String value = hopper.getProperties().get(EXTRACT_DATE);
		return value == null || value.isBlank() ? null : OffsetDateTime.parse(value).toInstant();
	}

}
