package lk.routerank.fraud;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Save limits per device and per IP, on top of the routes module's per-user limit. Kept in memory: the API runs as
 * one instance. The per-IP limit is only a loose ceiling, because Sri Lankan mobile networks put many people behind
 * one address. Over a limit answers 429.
 */
@Service
public class SaveLimits {

	private final Window perDevice;

	private final Window perIp;

	SaveLimits(FraudProperties props, Clock clock) {
		this.perDevice = new Window(props.limits().perDevice(), props.limits().perDeviceWindow(), clock);
		this.perIp = new Window(props.limits().perIp(), props.limits().perIpWindow(), clock);
	}

	/**
	 * Counts one save.
	 *
	 * @param device the device signal's hash, or null when there is none
	 * @param ip the visitor's address as the proxy reported it, or null
	 */
	public void check(byte[] device, String ip) {
		if (device != null) {
			perDevice.check(HexFormat.of().formatHex(device));
		}
		if (ip != null && !ip.isBlank()) {
			perIp.check(ip);
		}
	}

	/** Forgets keys with no saves left in their window, so the maps don't grow forever. */
	void prune() {
		perDevice.prune();
		perIp.prune();
	}

	/** A sliding-window count per key. */
	static class Window {

		private final int limit;

		private final Duration window;

		private final Clock clock;

		private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

		Window(int limit, Duration window, Clock clock) {
			this.limit = limit;
			this.window = window;
			this.clock = clock;
		}

		void check(String key) {
			long now = clock.millis();
			long since = now - window.toMillis();
			Deque<Long> times = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
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

		void prune() {
			long since = clock.millis() - window.toMillis();
			hits.entrySet().removeIf(e -> {
				synchronized (e.getValue()) {
					return e.getValue().isEmpty() || e.getValue().peekLast() <= since;
				}
			});
		}

	}

}
