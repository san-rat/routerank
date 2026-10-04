package lk.routerank.roads;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import com.graphhopper.GraphHopper;

/**
 * Builds the routing graph from the filtered main-roads extract, outside the API (locally or in CI; never on the
 * VM). Run with {@code ./gradlew buildGraph -Posm=... -Pout=...}; see data/README.md.
 */
public final class BuildGraph {

	private BuildGraph() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 2) {
			System.err.println("usage: BuildGraph MAIN_ROADS.osm.pbf OUT_DIR");
			System.exit(2);
		}
		Path osm = Path.of(args[0]);
		Path out = Path.of(args[1]);
		if (Files.exists(out)) {
			System.err.println(out + " already exists; graphs are never overwritten (name a new folder)");
			System.exit(1);
		}
		GraphHopper hopper = RoadGraph.create(out.toString(), osm.toString(), false);
		hopper.importOrLoad();
		Instant date = RoadGraph.extractDate(hopper);
		int edges = hopper.getBaseGraph().getEdges();
		hopper.close();
		if (date == null) {
			System.err.println("The extract has no replication timestamp; the API could not check it against import_run");
			System.exit(1);
		}
		System.out.printf("Built %s: %,d edges, extract date %s%n", out, edges, date);
	}

}
