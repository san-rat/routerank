package lk.routerank.fraud;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Device signals: the browser sends FingerprintJS's {@code visitorId}; only an HMAC of it with a server secret is
 * stored ({@code device_signal}), and it is deleted 90 days after it was last seen. Free FingerprintJS can give
 * identical budget phones the same ID, so it is one signal among several, never a reason to ban.
 */
@Service
public class Devices {

	/** FingerprintJS's visitorId is 32 hex characters; anything much longer isn't one. */
	static final int MAX_ID_LENGTH = 64;

	private static final Logger log = LoggerFactory.getLogger(Devices.class);

	private final FraudProperties props;

	private final JdbcClient jdbc;

	private final Clock clock;

	Devices(FraudProperties props, JdbcClient jdbc, Clock clock) {
		this.props = props;
		this.jdbc = jdbc;
		this.clock = clock;
		if (!props.devicesEnabled()) {
			log.warn("routerank.fraud.device-hmac-key is not set: device signals are not stored");
		}
	}

	/**
	 * Records that this account used this device.
	 *
	 * @return the device's hash, or empty when there is no usable signal (or no key)
	 */
	public Optional<byte[]> record(long userId, String visitorId) {
		Optional<byte[]> hash = hash(visitorId);
		hash.ifPresent(h -> jdbc.sql("""
				INSERT INTO device_signal (user_id, device_hash, first_seen, last_seen) VALUES (:user, :hash, :now, :now)
				ON CONFLICT (user_id, device_hash) DO UPDATE SET last_seen = EXCLUDED.last_seen""")
			.param("user", userId)
			.param("hash", h)
			.param("now", Timestamp.from(clock.instant()))
			.update());
		return hash;
	}

	Optional<byte[]> hash(String visitorId) {
		if (!props.devicesEnabled() || visitorId == null || visitorId.isBlank() || visitorId.length() > MAX_ID_LENGTH) {
			return Optional.empty();
		}
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(props.deviceHmacKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return Optional.of(mac.doFinal(visitorId.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Deletes signals not seen for 90 days, every night. */
	@Scheduled(cron = "0 40 3 * * *", zone = "UTC")
	void deleteOld() {
		int deleted = jdbc.sql("DELETE FROM device_signal WHERE last_seen < :before")
			.param("before", Timestamp.from(clock.instant().minus(props.deviceRetention())))
			.update();
		if (deleted > 0) {
			log.info("Deleted {} device signals not seen for {}", deleted, props.deviceRetention());
		}
	}

}
