package lk.routerank.scoring;

/**
 * A road segment with points this run, as the stretch builder sees it.
 *
 * @param link a slip road or junction link: folded into the neighbouring stretch, never listed on its own
 * @param name the road's English name, or null
 * @param ref the road's number ("A4"), or null
 * @param points 3 / 2 / 1 from each counted route that covers it
 * @param people distinct voters behind those points
 * @param votes how many of those people have it in their #1, #2 and #3 route
 * @param from one end, shared with the segments it joins
 * @param to the other end
 * @param line the segment as {@code [lon, lat]} pairs, for the heatmap
 */
record ScoredSegment(long id, String province, boolean link, String name, String ref, double lengthM, int points,
		int people, int[] votes, Node from, Node to, double[][] line) {

	/**
	 * A segment end. Neighbouring segments share their end points exactly (the import cuts them from the same
	 * lines), so the key is the position rounded to 1e-6 degrees (about 0.1 m).
	 */
	record Node(long key, double lon, double lat) {

		static Node at(double lon, double lat) {
			long x = Math.round(lon * 1e6);
			long y = Math.round(lat * 1e6);
			return new Node(x * 1_000_000_000L + y, lon, lat);
		}

	}

}
