package lk.routerank.routes;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A per-user sliding-window limit, kept in memory (the API runs as one instance). Over the limit answers 429.
 */
class RateLimiter {

	private final int limit;

	private final Duration window;

	private final Clock clock;

	private final Map<Long, Deque<Long>> hits = new ConcurrentHashMap<>();

	RateLimiter(int limit, Duration window, Clock clock) {
		this.limit = limit;
		this.window = window;
		this.clock = clock;
	}

	void check(long userId) {
		long now = clock.millis();
		long since = now - window.toMillis();
		Deque<Long> times = hits.computeIfAbsent(userId, id -> new ArrayDeque<>());
		synchronized (times) {
			while (!times.isEmpty() && times.peekFirst() <= since) {
				times.pollFirst();
			}
			if (times.size() >= limit) {
				throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests; try again shortly");
			}
			times.addLast(now);
		}
	}

}
