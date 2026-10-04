package lk.routerank.fraud;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lk.routerank.fraud.Flags.Reason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The hourly fraud scan. New accounts' votes count only after 24 hours, so an hourly scan holds a burst well before
 * it would count. Bursts are checked first, so the device trigger can see which accounts they flagged.
 */
@Service
public class FraudScan {

	private static final Logger log = LoggerFactory.getLogger(FraudScan.class);

	private final Flags flags;

	private final Holds holds;

	private final SaveLimits limits;

	private final FraudProperties props;

	private final Clock clock;

	FraudScan(Flags flags, Holds holds, SaveLimits limits, FraudProperties props, Clock clock) {
		this.flags = flags;
		this.holds = holds;
		this.limits = limits;
		this.props = props;
		this.clock = clock;
	}

	@Scheduled(cron = "${routerank.fraud.scan-schedule:0 15 * * * *}", zone = "UTC")
	void scheduled() {
		Result result = run();
		if (result.burst() + result.sharedDevice() > 0) {
			log.info("Fraud scan held {} accounts in bursts and {} on shared devices", result.burst(), result.sharedDevice());
		}
	}

	/** Runs the scan now. */
	public Result run() {
		int burst = bursts();
		int device = 0;
		Set<Long> flaggedOtherwise = flags.flaggedForOtherReasons(Reason.SHARED_DEVICE);
		for (Flags.Device d : flags.sharedDevices(props.triggers().sharedDeviceWithOtherTrigger())) {
			device += holds.checkDevice(d.hash(), d.users(), flaggedOtherwise).size();
		}
		limits.prune();
		return new Result(burst, device);
	}

	/**
	 * Five or more accounts made within an hour of each other whose routes overlap: groups of accounts joined by
	 * overlapping routes, then, within each group, every account in some hour that holds five or more of them.
	 */
	private int bursts() {
		FraudProperties.Triggers t = props.triggers();
		Instant now = clock.instant();
		Map<Long, Instant> created = flags.newAccounts(now.minus(t.burstLookback()));
		Map<Long, Long> parent = new HashMap<>();
		for (Flags.Pair p : flags.overlappingNewAccounts(now.minus(t.burstLookback()), t.burstWindow(), t.burstOverlap())) {
			union(parent, p.a(), p.b());
		}
		Map<Long, List<Long>> groups = new HashMap<>();
		for (Long account : parent.keySet()) {
			groups.computeIfAbsent(find(parent, account), k -> new ArrayList<>()).add(account);
		}
		int held = 0;
		for (List<Long> group : groups.values()) {
			Set<Long> burst = inSomeWindow(group, created, t.burstWindow(), t.burstAccounts());
			if (burst.isEmpty()) {
				continue;
			}
			String cluster = "burst:" + group.stream().min(Long::compare).orElseThrow();
			for (Long account : burst) {
				if (flags.flag(account, Reason.BURST, cluster, Map.of("accounts", burst.size()), props.weights().burst(), now)) {
					held++;
				}
			}
		}
		return held;
	}

	/** The accounts that fall in some window of this length holding at least {@code min} of them. */
	static Set<Long> inSomeWindow(List<Long> accounts, Map<Long, Instant> created, Duration window, int min) {
		List<Long> byTime = accounts.stream()
			.filter(created::containsKey)
			.sorted(Comparator.comparing(created::get))
			.toList();
		Set<Long> result = new HashSet<>();
		int start = 0;
		for (int end = 0; end < byTime.size(); end++) {
			while (Duration.between(created.get(byTime.get(start)), created.get(byTime.get(end))).compareTo(window) > 0) {
				start++;
			}
			if (end - start + 1 >= min) {
				result.addAll(byTime.subList(start, end + 1));
			}
		}
		return result;
	}

	private static void union(Map<Long, Long> parent, long a, long b) {
		long ra = find(parent, a);
		long rb = find(parent, b);
		if (ra != rb) {
			parent.put(Math.max(ra, rb), Math.min(ra, rb));
		}
	}

	private static long find(Map<Long, Long> parent, long x) {
		parent.putIfAbsent(x, x);
		long root = x;
		while (parent.get(root) != root) {
			root = parent.get(root);
		}
		parent.put(x, root);
		return root;
	}

	/** How many accounts this run held, by trigger. */
	public record Result(int burst, int sharedDevice) {
	}

}
