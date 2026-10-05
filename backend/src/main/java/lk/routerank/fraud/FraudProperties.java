package lk.routerank.fraud;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Anti-fraud settings (see the Architecture doc, "Free anti-fraud layers"). Thresholds live here so they can be
 * tuned without a deploy of new code.
 *
 * @param deviceHmacKey the server secret device signals are hashed with (environment only); without it no device
 * signal is stored and the device trigger and limit are off
 * @param deviceRetention device signals are deleted this long after they were last seen
 * @param scanSchedule cron (UTC) for the fraud scan; "-" turns the schedule off (tests)
 */
@ConfigurationProperties("routerank.fraud")
record FraudProperties(String deviceHmacKey, @DefaultValue("90d") Duration deviceRetention,
		@DefaultValue("0 15 * * * *") String scanSchedule, @DefaultValue Triggers triggers, @DefaultValue Limits limits,
		@DefaultValue Weights weights) {

	boolean devicesEnabled() {
		return deviceHmacKey != null && !deviceHmacKey.isBlank();
	}

	/**
	 * When an account is held.
	 *
	 * @param sharedDeviceAccounts this many accounts on one device hold them all, on their own
	 * @param sharedDeviceWithOtherTrigger this many on one device hold the ones another trigger also flagged
	 * (free FingerprintJS can give identical budget phones the same ID)
	 * @param burstAccounts this many accounts made within {@code burstWindow} of each other, with overlapping routes
	 * @param burstOverlap two routes overlap when they share at least this share of the longer one's length
	 * @param burstLookback the scan looks at accounts made this recently
	 */
	record Triggers(@DefaultValue("5") int sharedDeviceAccounts, @DefaultValue("3") int sharedDeviceWithOtherTrigger,
			@DefaultValue("5") int burstAccounts, @DefaultValue("60m") Duration burstWindow,
			@DefaultValue("0.7") double burstOverlap, @DefaultValue("48h") Duration burstLookback) {
	}

	/** Saves allowed per device and per IP (only a loose ceiling: mobile networks share IPs). */
	record Limits(@DefaultValue("30") int perDevice, @DefaultValue("10m") Duration perDeviceWindow,
			@DefaultValue("300") int perIp, @DefaultValue("1h") Duration perIpWindow) {
	}

	/** Each open flag adds its weight to the account's trust score, which only sorts the review queue. */
	record Weights(@DefaultValue("2") double sharedDevice, @DefaultValue("1") double burst,
			@DefaultValue("3") double honeypot) {

		double of(Flags.Reason reason) {
			return switch (reason) {
				case SHARED_DEVICE -> sharedDevice;
				case BURST -> burst;
				case HONEYPOT -> honeypot;
			};
		}

	}

}
