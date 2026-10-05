package lk.routerank.busroutes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lk.routerank.roads.LatLon;

/**
 * Plain geometry on short lines in Sri Lanka: distances on a local flat projection, which is accurate to well
 * under a metre over the few kilometres these checks look at.
 */
final class Lines {

	static final double EARTH_M = 6_371_008.8;

	private Lines() {
	}

	static double distanceM(LatLon a, LatLon b) {
		double x = Math.toRadians(b.lon() - a.lon()) * Math.cos(Math.toRadians((a.lat() + b.lat()) / 2));
		double y = Math.toRadians(b.lat() - a.lat());
		return Math.hypot(x, y) * EARTH_M;
	}

	static double lengthM(List<LatLon> line) {
		double length = 0;
		for (int i = 1; i < line.size(); i++) {
			length += distanceM(line.get(i - 1), line.get(i));
		}
		return length;
	}

	/**
	 * A line cut at its nearest point to {@code p}.
	 *
	 * @param before the line from its start to the cut
	 * @param after the line from the cut to its end
	 * @param distanceM how far the cut is from {@code p}
	 */
	record Cut(List<LatLon> before, List<LatLon> after, double distanceM) {
	}

	static Cut cut(List<LatLon> line, LatLon p) {
		double best = Double.POSITIVE_INFINITY;
		int bestIndex = 0;
		LatLon bestPoint = line.getFirst();
		for (int i = 0; i + 1 < line.size(); i++) {
			LatLon q = nearestOnSegment(p, line.get(i), line.get(i + 1));
			double d = distanceM(p, q);
			if (d < best) {
				best = d;
				bestIndex = i;
				bestPoint = q;
			}
		}
		if (line.size() == 1) {
			best = distanceM(p, bestPoint);
		}
		List<LatLon> before = new ArrayList<>(line.subList(0, bestIndex + 1));
		before.add(bestPoint);
		List<LatLon> after = new ArrayList<>();
		after.add(bestPoint);
		after.addAll(line.subList(Math.min(bestIndex + 1, line.size()), line.size()));
		return new Cut(before, after, best);
	}

	/** The point of segment a–b nearest to p. */
	static LatLon nearestOnSegment(LatLon p, LatLon a, LatLon b) {
		double cos = Math.cos(Math.toRadians(p.lat()));
		double ax = (a.lon() - p.lon()) * cos;
		double ay = a.lat() - p.lat();
		double bx = (b.lon() - p.lon()) * cos;
		double by = b.lat() - p.lat();
		double dx = bx - ax;
		double dy = by - ay;
		double len2 = dx * dx + dy * dy;
		double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
		return new LatLon(a.lat() + t * (b.lat() - a.lat()), a.lon() + t * (b.lon() - a.lon()));
	}

	/**
	 * Lines indexed on a grid of about 55 m, so "is this point within a few metres of the bus?" doesn't scan every
	 * segment of the bus route.
	 */
	static final class Near {

		private static final double CELL = 0.0005;

		private final List<LatLon[]> segments = new ArrayList<>();

		private final Map<Long, List<Integer>> cells = new HashMap<>();

		private final double withinM;

		Near(List<List<LatLon>> lines, double withinM) {
			this.withinM = withinM;
			double pad = withinM / 111_000.0 * 2;
			for (List<LatLon> line : lines) {
				for (int i = 0; i + 1 < line.size(); i++) {
					LatLon a = line.get(i);
					LatLon b = line.get(i + 1);
					int index = segments.size();
					segments.add(new LatLon[] { a, b });
					for (long x = cell(Math.min(a.lon(), b.lon()) - pad); x <= cell(Math.max(a.lon(), b.lon()) + pad); x++) {
						for (long y = cell(Math.min(a.lat(), b.lat()) - pad); y <= cell(Math.max(a.lat(), b.lat()) + pad); y++) {
							cells.computeIfAbsent(key(x, y), k -> new ArrayList<>()).add(index);
						}
					}
				}
			}
		}

		boolean contains(LatLon p) {
			for (int index : cells.getOrDefault(key(cell(p.lon()), cell(p.lat())), List.of())) {
				LatLon[] s = segments.get(index);
				if (distanceM(p, nearestOnSegment(p, s[0], s[1])) <= withinM) {
					return true;
				}
			}
			return false;
		}

		/** The share of a line's length that runs within {@code withinM} of these lines, sampled every 10 m. */
		double shareOf(List<LatLon> line) {
			double total = 0;
			double near = 0;
			for (int i = 0; i + 1 < line.size(); i++) {
				LatLon a = line.get(i);
				LatLon b = line.get(i + 1);
				double length = distanceM(a, b);
				int steps = Math.max(1, (int) Math.ceil(length / 10));
				for (int s = 0; s < steps; s++) {
					double t = (s + 0.5) / steps;
					LatLon p = new LatLon(a.lat() + t * (b.lat() - a.lat()), a.lon() + t * (b.lon() - a.lon()));
					if (contains(p)) {
						near += length / steps;
					}
				}
				total += length;
			}
			return total == 0 ? 0 : near / total;
		}

		private static long cell(double degrees) {
			return (long) Math.floor(degrees / CELL);
		}

		private static long key(long x, long y) {
			return x * 1_000_003L + y;
		}

	}

}
