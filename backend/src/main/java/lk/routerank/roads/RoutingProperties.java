package lk.routerank.roads;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the GraphHopper graph lives. In production the graph is built elsewhere and downloaded by the deploy
 * (see the Architecture doc, The routing graph); the API only loads it. {@code osmFile} is for development and
 * tests: when set and there is no graph yet, the API builds one from that file on startup.
 *
 * @param graphLocation the graph directory; blank turns routing off (adding routes then answers 503)
 * @param osmFile the filtered main-roads extract to build from when the graph is missing (not in production)
 */
@ConfigurationProperties("routerank.routing")
record RoutingProperties(String graphLocation, String osmFile) {

	boolean enabled() {
		return graphLocation != null && !graphLocation.isBlank();
	}

}
