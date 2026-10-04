package lk.routerank.routes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import lk.routerank.routes.Slots.Lock;
import lk.routerank.routes.Slots.Placement;
import org.junit.jupiter.api.Test;

class SlotsTests {

	static final long NEW = -1;

	@Test
	void aNewRouteTakesAFreeSlot() {
		Placement p = Slots.place(Map.of(10L, 1), null, NEW, 2).orElseThrow();
		assertThat(p.slots()).isEqualTo(Map.of(10L, 1, NEW, 2));
		assertThat(p.touched()).containsExactly(2);
	}

	@Test
	void takingAnOccupiedSlotPushesTheOthersDown() {
		Placement p = Slots.place(Map.of(10L, 1, 11L, 2), null, NEW, 1).orElseThrow();
		assertThat(p.slots()).isEqualTo(Map.of(NEW, 1, 10L, 2, 11L, 3));
		assertThat(p.touched()).isEqualTo(Set.of(1, 2, 3));
	}

	@Test
	void pushingStopsAtTheFirstFreeSlot() {
		Placement p = Slots.place(Map.of(10L, 1, 11L, 3), null, NEW, 1).orElseThrow();
		assertThat(p.slots()).isEqualTo(Map.of(NEW, 1, 10L, 2, 11L, 3));
		assertThat(p.touched()).isEqualTo(Set.of(1, 2)); // #3 keeps its route
	}

	@Test
	void withNoFreeSlotBelowRoutesMoveUpInstead() {
		// #2 and #3 taken, #1 free: the new route goes in #2 and the old #2 moves up, keeping the order
		Placement p = Slots.place(Map.of(10L, 2, 11L, 3), null, NEW, 2).orElseThrow();
		assertThat(p.slots()).isEqualTo(Map.of(10L, 1, NEW, 2, 11L, 3));
		assertThat(p.touched()).isEqualTo(Set.of(1, 2));
	}

	@Test
	void threeRoutesLeaveNoRoomForAFourth() {
		assertThat(Slots.place(Map.of(10L, 1, 11L, 2, 12L, 3), null, NEW, 2)).isEmpty();
	}

	@Test
	void editingARouteInPlaceTouchesOnlyItsSlot() {
		Placement p = Slots.place(Map.of(10L, 1, 11L, 2), 11L, 11L, 2).orElseThrow();
		assertThat(p.slots()).isEqualTo(Map.of(10L, 1, 11L, 2));
		assertThat(p.touched()).containsExactly(2);
	}

	@Test
	void movingARouteUpPushesTheOneInTheWay() {
		Placement p = Slots.place(Map.of(10L, 1, 11L, 2, 12L, 3), 12L, 12L, 1).orElseThrow();
		assertThat(p.slots()).isEqualTo(Map.of(12L, 1, 10L, 2, 11L, 3));
		assertThat(p.touched()).isEqualTo(Set.of(1, 2, 3));
	}

	@Test
	void reorderTouchesOnlySlotsThatGetADifferentRoute() {
		assertThat(Slots.touchedByReorder(Map.of(10L, 1, 11L, 2, 12L, 3), Map.of(10L, 2, 11L, 1, 12L, 3)))
			.isEqualTo(Set.of(1, 2));
		// Moving a route into an empty slot touches that slot only; the slot it left is just empty
		assertThat(Slots.touchedByReorder(Map.of(10L, 1), Map.of(10L, 3))).containsExactly(3);
	}

	@Test
	void aChangeLocksTheSlotFor24HoursWithA15MinuteGraceWindow() {
		Instant changed = Instant.parse("2026-10-04T08:00:00Z");
		Lock lock = new Lock(1, changed);
		assertThat(lock.changeable(changed.plus(Duration.ofMinutes(14)))).isTrue();
		assertThat(lock.changeStartsLock(changed.plus(Duration.ofMinutes(14)))).isFalse(); // inside the grace window
		assertThat(lock.changeable(changed.plus(Duration.ofMinutes(15)))).isFalse();
		assertThat(lock.changeable(changed.plus(Duration.ofHours(23)))).isFalse();
		assertThat(lock.lockedUntil()).isEqualTo(changed.plus(Duration.ofHours(24)));
		assertThat(lock.changeable(changed.plus(Duration.ofHours(24)))).isTrue();
		assertThat(lock.changeStartsLock(changed.plus(Duration.ofHours(24)))).isTrue();
	}

	@Test
	void aSlotThatNeverChangedIsFree() {
		Lock lock = new Lock(2, null);
		assertThat(lock.free(Instant.now())).isTrue();
		assertThat(lock.changeStartsLock(Instant.now())).isTrue();
		assertThat(lock.lockedUntil()).isNull();
	}

}
