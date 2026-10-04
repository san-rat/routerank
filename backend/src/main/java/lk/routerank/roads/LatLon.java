package lk.routerank.roads;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

/** A point in EPSG:4326. The bounds are a box around Sri Lanka, so far-off points are rejected early. */
public record LatLon(@DecimalMin("5.5") @DecimalMax("10.2") double lat, @DecimalMin("79.3") @DecimalMax("82.2") double lon) {
}
