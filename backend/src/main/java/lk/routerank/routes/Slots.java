package lk.routerank.routes;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The slot rules, without the database: where routes end up when one takes an occupied slot, and the 24-hour
 * cooldown.
 *
 * <p><b>Cooldown.</b> Any change to a slot (a new route, an edit, or a reorder that moves a route into it) starts a
 * 24-hour lock on that slot. For 15 minutes after the change that started the lock, the slot can still be changed
 * (to fix mistakes) without restarting the lock. Removing a route is always allowed and doesn't touch the lock: a
 * freed slot stays locked until its timer ends.
 */
final class Slots {

	static final int COUNT = 3;

	static final Duration LOCK = Duration.ofHours(24);

	static final Duration GRACE = Duration.ofMinutes(15);

	private Slots() {
	}

	/**
	 * Where every route goes when {@code moving} (a route already in a slot, or {@code null} for a new route) is put
	 * in {@code target}. Taking an occupied slot pushes routes down (#1 → #2) into the nearest free slot below; if
	 * there is none, routes move up into the free slot above instead, so the order of the others never changes.
	 *
	 * @param current route ID → slot for the user's routes that are not removed
	 * @return the new route ID → slot (the moving route under {@code movingKey}) and the slots whose route changed,
	 *     or empty when all three slots are taken by other routes
	 */
	static Optional<Placement> place(Map<Long, Integer> current, Long moving, long movingKey, int target) {
		Long[] bySlot = new Long[COUNT + 1];
		current.forEach((id, slot) -> {
			if (!id.equals(moving)) {
				bySlot[slot] = id;
			}
		});
		if (bySlot[target] != null) {
			int free = -1;
			for (int s = target + 1; s <= COUNT && free < 0; s++) {
				if (bySlot[s] == null) {
					free = s;
				}
			}
			if (free > 0) {
				System.arraycopy(bySlot, target, bySlot, target + 1, free - target);
			}
			else {
				for (int s = target - 1; s >= 1 && free < 0; s--) {
					if (bySlot[s] == null) {
						free = s;
					}
				}
				if (free < 0) {
					return Optional.empty();
				}
				System.arraycopy(bySlot, free + 1, bySlot, free, target - free);
			}
		}
		bySlot[target] = movingKey;

		Map<Long, Integer> next = new HashMap<>();
		Set<Integer> touched = new HashSet<>();
		for (int s = 1; s <= COUNT; s++) {
			Long id = bySlot[s];
			if (id == null) {
				continue;
			}
			next.put(id, s);
			Long before = id == movingKey ? moving : id;
			if (id == movingKey || !Objects.equals(current.get(before), s)) {
				touched.add(s);
			}
		}
		return Optional.of(new Placement(next, touched));
	}

	/** The slots whose route changes when routes are reordered to {@code next} (route ID → slot). */
	static Set<Integer> touchedByReorder(Map<Long, Integer> current, Map<Long, Integer> next) {
		Set<Integer> touched = new HashSet<>();
		next.forEach((id, slot) -> {
			if (!slot.equals(current.get(id))) {
				touched.add(slot);
			}
		});
		return touched;
	}

	/**
	 * A slot's lock, from the change that started it.
	 * @param startedAt the last change that started a lock on this slot, or {@code null} if never changed
	 */
	record Lock(int slot, Instant startedAt) {

		boolean free(Instant now) {
			return startedAt == null || !now.isBefore(lockedUntil());
		}

		boolean inGrace(Instant now) {
			return startedAt != null && now.isBefore(startedAt.plus(GRACE));
		}

		/** Changing the slot now is allowed: it is free, or still in the grace window. */
		boolean changeable(Instant now) {
			return free(now) || inGrace(now);
		}

		/** A change now starts a new lock (rather than falling inside the current one's grace window). */
		boolean changeStartsLock(Instant now) {
			return free(now);
		}

		Instant lockedUntil() {
			return startedAt == null ? null : startedAt.plus(LOCK);
		}

	}

	record Placement(Map<Long, Integer> slots, Set<Integer> touched) {
	}

}
