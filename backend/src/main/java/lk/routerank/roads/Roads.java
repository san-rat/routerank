package lk.routerank.roads;

import static com.graphhopper.util.DistanceCalcEarth.DIST_EARTH;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.ResponsePath;
import com.graphhopper.routing.ev.Subnetwork;
import com.graphhopper.routing.util.DefaultSnapFilter;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.Parameters;
import com.graphhopper.util.PMap;
import com.graphhopper.util.PointList;
import com.graphhopper.util.details.PathDetail;
import com.graphhopper.util.exceptions.ConnectionNotFoundException;
import com.graphhopper.util.exceptions.MaximumNodesExceededException;
import com.graphhopper.util.exceptions.PointNotFoundException;
import lk.routerank.roads.RoutePlan.Leg;
import lk.routerank.roads.RoutePlan.SnappedPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Routing on main roads, matching paths onto road segments, and place names: the roads module's API.
 *
 * <p>On startup it loads the GraphHopper graph and refuses to start if the graph was built from a different
 * extract than the newest {@code import_run}, so paths and segments always come from the same OSM data.
 */
@Service
public class Roads implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(Roads.class);

	private static final List<String> DETAILS = List.of("osm_way_id", Parameters.Details.STREET_NAME,
			Parameters.Details.STREET_REF);

	private final GraphHopper hopper;

	private final EdgeFilter snapFilter;

	private final CurrentImport current;

	private final SegmentMatcher matcher;

	private final Places places;

	Roads(RoutingProperties props, JdbcClient jdbc) {
		this.matcher = new SegmentMatcher(jdbc);
		this.places = new Places(jdbc);
		if (!props.enabled()) {
			log.warn("routerank.routing.graph-location is not set: adding routes is turned off");
			this.hopper = null;
			this.snapFilter = null;
			this.current = null;
			return;
		}
		boolean build = props.osmFile() != null && !props.osmFile().isBlank();
		this.hopper = RoadGraph.create(props.graphLocation(), build ? props.osmFile() : null, !build);
		if (build) {
			hopper.importOrLoad();
		}
		else if (!hopper.load()) {
			throw new IllegalStateException("No routing graph at " + props.graphLocation());
		}
		this.current = currentImport(jdbc);
		Instant graphDate = RoadGraph.extractDate(hopper);
		if (!current.extractDate().equals(graphDate)) {
			hopper.close();
			throw new IllegalStateException("The routing graph was built from the extract of " + graphDate
					+ " but import_run " + current.id() + " is from " + current.extractDate()
					+ "; build the graph from the same extract as the road segments");
		}
		var profile = hopper.getProfile(RoadGraph.PROFILE);
		this.snapFilter = new DefaultSnapFilter(hopper.createWeighting(profile, new PMap()),
				hopper.getEncodingManager().getBooleanEncodedValue(Subnetwork.key(RoadGraph.PROFILE)));
		log.info("Routing graph loaded: {} edges, extract {}, import_run {}", hopper.getBaseGraph().getEdges(),
				graphDate, current.id());
	}

	private static CurrentImport currentImport(JdbcClient jdbc) {
		return jdbc.sql("""
				SELECT id, extract_date FROM import_run
				WHERE finished_at IS NOT NULL ORDER BY id DESC LIMIT 1""")
			.query(CurrentImport.class)
			.optional()
			.orElseThrow(() -> new IllegalStateException("No road import in the database (import_run is empty)"));
	}

	public boolean enabled() {
		return hopper != null;
	}

	/** The nearest main road to a point, or a {@code snapped} of null when there is none nearby. */
	public SnappedPoint snap(LatLon point) {
		Snap snap = graph().getLocationIndex().findClosest(point.lat(), point.lon(), snapFilter);
		if (!snap.isValid()) {
			return new SnappedPoint(point, null, Double.POSITIVE_INFINITY);
		}
		var p = snap.getSnappedPoint();
		return new SnappedPoint(point, new LatLon(p.getLat(), p.getLon()), snap.getQueryDistance());
	}

	/**
	 * Works out a route through the points (start, waypoints, end): each point snapped to the nearest main road,
	 * the way there through them, the way back from end to start, and the segments either direction covers.
	 */
	public RoutePlan plan(List<LatLon> points) {
		List<SnappedPoint> snapped = points.stream().map(this::snap).toList();
		if (snapped.stream().anyMatch(p -> p.snapped() == null)) {
			return new RoutePlan(snapped, null, null, Set.of());
		}
		List<LatLon> via = snapped.stream().map(SnappedPoint::snapped).toList();
		ResponsePath out = route(via);
		ResponsePath back = out == null ? null : route(List.of(via.getLast(), via.getFirst()));
		if (out == null) {
			return new RoutePlan(snapped, null, null, Set.of());
		}
		Set<Long> segments = matcher.match(current.id(), out, back);
		Leg outLeg = new Leg(line(out.getPoints()), out.getDistance(), List.of(), List.of());
		Leg backLeg = back == null ? null : WayBack.describe(outLeg.line(), back);
		return new RoutePlan(snapped, outLeg, backLeg, segments);
	}

	/** A route's auto-name from the nearest place to each end, e.g. "Pettah → Horana". */
	public String name(LatLon start, LatLon end) {
		String from = places.nearestName(current.id(), start);
		String to = places.nearestName(current.id(), end);
		return Objects.requireNonNullElse(from, "Start") + " → " + Objects.requireNonNullElse(to, "End");
	}

	/** The nearest place's name, or null when the import has no places. */
	public String placeName(LatLon point) {
		return places.nearestName(current.id(), point);
	}

	/** Towns, suburbs and quarters a line passes within 400 m of, in order (at most {@code limit}). */
	public List<String> townsAlong(List<LatLon> line, int limit) {
		StringBuilder wkt = new StringBuilder("LINESTRING(");
		for (int i = 0; i < line.size(); i++) {
			wkt.append(i > 0 ? ", " : "").append(line.get(i).lon()).append(' ').append(line.get(i).lat());
		}
		return places.along(current.id(), wkt.append(')').toString(), 400, limit);
	}

	/** Places whose English name starts with the text, biggest kinds first. */
	public List<Place> searchPlaces(String prefix, int limit) {
		if (!enabled()) {
			throw new RoutingUnavailableException();
		}
		return places.search(current.id(), prefix, limit);
	}

	private ResponsePath route(List<LatLon> via) {
		GHRequest request = new GHRequest(via.stream().map(p -> new com.graphhopper.util.shapes.GHPoint(p.lat(), p.lon())).toList())
			.setProfile(RoadGraph.PROFILE)
			.setPathDetails(DETAILS)
			.putHint(Parameters.Routing.INSTRUCTIONS, false);
		GHResponse response = graph().route(request);
		if (!response.hasErrors()) {
			return response.getBest();
		}
		Throwable error = response.getErrors().getFirst();
		if (error instanceof ConnectionNotFoundException || error instanceof PointNotFoundException
				|| error instanceof MaximumNodesExceededException) {
			return null;
		}
		throw new IllegalStateException("Routing failed", error);
	}

	private GraphHopper graph() {
		if (hopper == null) {
			throw new RoutingUnavailableException();
		}
		return hopper;
	}

	static List<LatLon> line(PointList points) {
		List<LatLon> line = new ArrayList<>(points.size());
		for (int i = 0; i < points.size(); i++) {
			line.add(new LatLon(points.getLat(i), points.getLon(i)));
		}
		return line;
	}

	static double distanceM(LatLon a, LatLon b) {
		return DIST_EARTH.calcDist(a.lat(), a.lon(), b.lat(), b.lon());
	}

	/** The road name of each edge of a path (index i = points i to i+1), or null where it has none. */
	static String[] edgeNames(Map<String, List<PathDetail>> details, int edges) {
		String[] names = new String[edges];
		for (String key : List.of(Parameters.Details.STREET_REF, Parameters.Details.STREET_NAME)) {
			for (PathDetail d : details.getOrDefault(key, List.of())) {
				if (d.getValue() instanceof String s && !s.isBlank()) {
					for (int i = d.getFirst(); i < Math.min(d.getLast(), edges); i++) {
						names[i] = s; // names win over refs (A4) because they're written last
					}
				}
			}
		}
		return names;
	}

	@Override
	public void destroy() {
		if (hopper != null) {
			hopper.close();
		}
	}

}
