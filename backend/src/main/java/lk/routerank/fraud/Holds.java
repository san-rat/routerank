package lk.routerank.fraud;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lk.routerank.fraud.Flags.Reason;
import org.springframework.stereotype.Service;

/**
 * Holds accounts when a trigger fires (see {@link FraudProperties.Triggers}). Triggers alone decide holds: a held
 * account's routes, and any it saves later, stay out of the rankings until an admin releases it. Holds are never
 * turned into bans automatically.
 */
@Service
public class Holds {

	private final Flags flags;

	private final FraudProperties props;

	private final Clock clock;

	Holds(Flags flags, FraudProperties props, Clock clock) {
		this.flags = flags;
		this.props = props;
		this.clock = clock;
	}

	/** A save that filled in the hidden field: only a bot does that. */
	public void honeypot(long userId) {
		flags.flag(userId, Reason.HONEYPOT, "honeypot:" + userId, Map.of(), props.weights().honeypot(),
				clock.instant());
	}

	/** Checks the device trigger for a device that was just used to sign in or save. */
	public void deviceSeen(byte[] deviceHash) {
		checkDevice(deviceHash, flags.accountsOn(deviceHash), flags.flaggedForOtherReasons(Reason.SHARED_DEVICE));
	}

	/**
	 * Five or more accounts on one device hold them all; three or more hold only the ones another trigger flagged
	 * too, since free FingerprintJS can give identical budget phones the same ID.
	 *
	 * @return the accounts newly flagged
	 */
	List<Long> checkDevice(byte[] deviceHash, List<Long> accounts, Set<Long> flaggedOtherwise) {
		FraudProperties.Triggers t = props.triggers();
		List<Long> hold = new ArrayList<>();
		if (accounts.size() >= t.sharedDeviceAccounts()) {
			hold.addAll(accounts);
		}
		else if (accounts.size() >= t.sharedDeviceWithOtherTrigger()) {
			accounts.stream().filter(flaggedOtherwise::contains).forEach(hold::add);
		}
		Instant now = clock.instant();
		String cluster = "device:" + HexFormat.of().formatHex(deviceHash, 0, 6);
		return hold.stream()
			.filter(user -> flags.flag(user, Reason.SHARED_DEVICE, cluster, Map.of("accounts", accounts.size()),
					props.weights().sharedDevice(), now))
			.toList();
	}

}
