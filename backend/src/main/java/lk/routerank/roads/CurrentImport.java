package lk.routerank.roads;

import java.time.Instant;

/**
 * The OSM import that routes are matched against: the newest finished {@code import_run}. Road data only changes
 * with a re-import and a new graph, which means a redeploy, so it is read once at startup.
 */
record CurrentImport(long id, Instant extractDate) {
}
