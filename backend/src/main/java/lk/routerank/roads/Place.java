package lk.routerank.roads;

/** A city, town, suburb, quarter, neighbourhood or village with an English name, for the search box. */
public record Place(String name, String kind, double lat, double lon) {
}
