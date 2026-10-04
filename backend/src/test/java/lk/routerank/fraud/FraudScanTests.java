package lk.routerank.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** "Five or more accounts made within an hour": the window slides over each group of overlapping accounts. */
class FraudScanTests {

	static final Instant T = Instant.parse("2026-10-04T08:00:00Z");

	static Map<Long, Instant> made(long... minutes) {
		Map<Long, Instant> created = new java.util.HashMap<>();
		for (int i = 0; i < minutes.length; i++) {
			created.put((long) i + 1, T.plus(Duration.ofMinutes(minutes[i])));
		}
		return created;
	}

	@Test
	void fiveWithinAnHourAreABurst() {
		Map<Long, Instant> created = made(0, 10, 20, 30, 60);
		assertThat(FraudScan.inSomeWindow(List.of(1L, 2L, 3L, 4L, 5L), created, Duration.ofHours(1), 5))
			.containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
	}

	@Test
	void fiveSpreadOverMoreThanAnHourAreNot() {
		Map<Long, Instant> created = made(0, 20, 40, 60, 61);
		assertThat(FraudScan.inSomeWindow(List.of(1L, 2L, 3L, 4L, 5L), created, Duration.ofHours(1), 5)).isEmpty();
	}

	@Test
	void onlyTheAccountsInABusyHourAreHeld() {
		// A slow trickle, then five in 20 minutes: the trickle isn't held
		Map<Long, Instant> created = made(0, 200, 400, 401, 405, 410, 420);
		assertThat(FraudScan.inSomeWindow(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L), created, Duration.ofHours(1), 5))
			.containsExactlyInAnyOrder(3L, 4L, 5L, 6L, 7L);
	}

}
