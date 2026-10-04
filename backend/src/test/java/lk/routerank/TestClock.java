package lk.routerank;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** A clock tests can move forward, for the 24-hour slot cooldown and its 15-minute grace window. */
public class TestClock extends Clock {

	private Instant now = Instant.parse("2026-10-04T08:00:00Z");

	public synchronized void advance(Duration duration) {
		now = now.plus(duration);
	}

	@Override
	public synchronized Instant instant() {
		return now;
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}

	@TestConfiguration(proxyBeanMethods = false)
	public static class Config {

		@Bean
		@Primary
		TestClock testClock() {
			return new TestClock();
		}

	}

}
