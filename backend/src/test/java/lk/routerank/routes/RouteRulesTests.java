package lk.routerank.routes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import lk.routerank.TestClock;
import lk.routerank.TestcontainersConfiguration;
import lk.routerank.roads.LatLon;
import lk.routerank.roads.Roads;
import lk.routerank.routes.Problem.Code;
import lk.routerank.routes.Views.MyRoutes;
import lk.routerank.routes.Views.RouteView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Every route rule against the real Colombo test roads and PostGIS: overlap, the slot cooldown and its grace
 * window, slot shifting, removing, and the per-user lock.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, TestClock.Config.class })
class RouteRulesTests {

	static final AtomicInteger USERS = new AtomicInteger();

	@Autowired
	RouteService routes;

	@Autowired
	Roads roads;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TestClock clock;

	long user;

	// Trips on main roads (points snapped first, so none counts as a side road). Pettah→Borella, Town Hall→Thummulla
	// and Kollupitiya→Bambalapitiya share no road; Thummulla→Havelock meets Town Hall→Thummulla only at a junction;
	// Fort→Kollupitiya shares Galle Road with Kollupitiya→Bambalapitiya.
	RouteInput pettahBorella;

	RouteInput townHallThummulla;

	RouteInput kollupitiyaBambalapitiya;

	RouteInput thummullaHavelock;

	RouteInput fortKollupitiya;

	@BeforeEach
	void newUser() {
		user = jdbc.sql("""
				INSERT INTO app_user (google_sub, email, created_at, live_at)
				VALUES (:sub, :sub::text || '@example.com', :now::timestamptz, :now::timestamptz + interval '24 hours') RETURNING id""")
			.param("sub", "rules-" + USERS.incrementAndGet())
			.param("now", java.sql.Timestamp.from(clock.instant()))
			.query(Long.class)
			.single();
		pettahBorella = trip(6.9355, 79.8500, 6.9147, 79.8775);
		townHallThummulla = trip(6.9167, 79.8636, 6.8960, 79.8610);
		kollupitiyaBambalapitiya = trip(6.9147, 79.8488, 6.8890, 79.8553);
		thummullaHavelock = trip(6.8960, 79.8610, 6.8850, 79.8680);
		fortKollupitiya = trip(6.9344, 79.8428, 6.9147, 79.8488);
	}

	RouteInput trip(double lat1, double lon1, double lat2, double lon2) {
		return new RouteInput(roads.snap(new LatLon(lat1, lon1)).snapped(), roads.snap(new LatLon(lat2, lon2)).snapped(),
				List.of(), null, null);
	}

	static RouteInput inSlot(RouteInput input, int slot) {
		return new RouteInput(input.start(), input.end(), input.waypoints(), slot, null);
	}

	static List<Code> refusal(Runnable save) {
		try {
			save.run();
		}
		catch (RouteRefusedException e) {
			return e.problems().stream().map(Problem::code).toList();
		}
		throw new AssertionError("expected the save to be refused");
	}

	@Test
	void savesARouteWithItsSegmentsWaypointsAndName() {
		LatLon via = roads.snap(new LatLon(6.9200, 79.8600)).snapped();
		RouteInput input = new RouteInput(pettahBorella.start(), pettahBorella.end(), List.of(via), 1, null);
		RouteView saved = routes.create(user, input);

		assertThat(saved.slot()).isEqualTo(1);
		assertThat(saved.name()).isEqualTo("Fort → Borella");
		assertThat(saved.waypoints()).containsExactly(via);
		assertThat(saved.lengthOutM()).isPositive();
		assertThat(saved.lengthBackM()).isPositive();
		int segments = jdbc.sql("SELECT count(*) FROM route_segment WHERE route_id = :id AND scores")
			.param("id", saved.id()).query(Integer.class).single();
		assertThat(segments).isPositive();
		assertThat(routes.list(user).routes()).extracting(RouteView::id).containsExactly(saved.id());
	}

	@Test
	void theServerWorksTheRouteOutAgainFromThePoints() {
		// The preview and the save agree, because both come from the points alone
		var preview = routes.preview(user, pettahBorella);
		RouteView saved = routes.create(user, inSlot(pettahBorella, 1));
		assertThat(saved.lengthOutM()).isEqualTo(preview.lengthOutM());
		assertThat(saved.lengthBackM()).isEqualTo(preview.lengthBackM());
	}

	@Test
	void aSideRoadStartIsRefusedWithTheNearestMainRoad() {
		RouteInput lane = new RouteInput(new LatLon(6.9355, 79.8500), pettahBorella.end(), List.of(), 1, null);
		try {
			routes.create(user, lane);
			throw new AssertionError("expected a refusal");
		}
		catch (RouteRefusedException e) {
			assertThat(e.problems()).extracting(Problem::code).containsExactly(Code.SIDE_ROAD);
			assertThat(e.problems().getFirst().nearest()).isEqualTo(pettahBorella.start());
		}
		assertThat(routes.list(user).routes()).isEmpty();
	}

	@Test
	void routesMayNotShareRoadWithTheUsersOtherRoutes() {
		RouteView first = routes.create(user, inSlot(kollupitiyaBambalapitiya, 1));
		assertThat(refusal(() -> routes.create(user, inSlot(fortKollupitiya, 2)))).containsExactly(Code.OVERLAP);

		var preview = routes.preview(user, fortKollupitiya);
		assertThat(preview.problems()).extracting(Problem::code).containsExactly(Code.OVERLAP);
		assertThat(preview.problems().getFirst().routeId()).isEqualTo(first.id());
		assertThat(preview.problems().getFirst().routeName()).isEqualTo(first.name());
	}

	@Test
	void meetingAtAJunctionIsNotAnOverlap() {
		routes.create(user, inSlot(townHallThummulla, 1));
		RouteView second = routes.create(user, inSlot(thummullaHavelock, 2));
		assertThat(second.slot()).isEqualTo(2);
	}

	@Test
	void otherUsersMayOverlapFreely() {
		routes.create(user, inSlot(kollupitiyaBambalapitiya, 1));
		long before = user;
		newUser();
		assertThat(user).isNotEqualTo(before);
		assertThat(routes.create(user, inSlot(kollupitiyaBambalapitiya, 1)).slot()).isEqualTo(1);
	}

	@Test
	void editingARouteDoesNotOverlapItself() {
		RouteView saved = routes.create(user, inSlot(kollupitiyaBambalapitiya, 1));
		var preview = routes.preview(user, new RouteInput(kollupitiyaBambalapitiya.start(), kollupitiyaBambalapitiya.end(),
				List.of(), null, saved.id()));
		assertThat(preview.problems()).isEmpty();
		assertThat(routes.update(user, saved.id(), kollupitiyaBambalapitiya).id()).isEqualTo(saved.id());
	}

	@Test
	void aSlotCanChangeAgainWithin15MinutesThenLocksFor24Hours() {
		RouteView saved = routes.create(user, inSlot(pettahBorella, 1));
		Instant lockedAt = clock.instant();

		clock.advance(Duration.ofMinutes(10)); // grace window: fix a mistake
		routes.update(user, saved.id(), townHallThummulla);

		clock.advance(Duration.ofMinutes(6)); // 16 minutes after the first save
		try {
			routes.update(user, saved.id(), pettahBorella);
			throw new AssertionError("expected the slot to be locked");
		}
		catch (RouteRefusedException e) {
			assertThat(e.problems()).extracting(Problem::code).containsExactly(Code.SLOT_LOCKED);
			// The grace-window edit did not restart the 24 hours
			assertThat(e.problems().getFirst().until()).isEqualTo(lockedAt.plus(Duration.ofHours(24)));
		}
		MyRoutes mine = routes.list(user);
		assertThat(mine.slots().getFirst().lockedUntil()).isEqualTo(lockedAt.plus(Duration.ofHours(24)));
		assertThat(mine.slots().get(1).lockedUntil()).isNull();

		clock.advance(Duration.ofHours(24));
		assertThat(routes.update(user, saved.id(), pettahBorella).name()).isEqualTo("Fort → Borella");
	}

	@Test
	void removingIsAlwaysAllowedButTheFreedSlotStaysLocked() {
		RouteView saved = routes.create(user, inSlot(pettahBorella, 1));
		clock.advance(Duration.ofHours(1));
		routes.remove(user, saved.id());
		assertThat(routes.list(user).routes()).isEmpty();

		assertThat(refusal(() -> routes.create(user, inSlot(townHallThummulla, 1)))).containsExactly(Code.SLOT_LOCKED);
		assertThat(routes.create(user, inSlot(townHallThummulla, 2)).slot()).isEqualTo(2);

		// The removed route is kept (marked removed) for review
		Instant removedAt = jdbc.sql("SELECT removed_at FROM route WHERE id = :id").param("id", saved.id())
			.query(Instant.class).single();
		assertThat(removedAt).isNotNull();
	}

	@Test
	void takingAnOccupiedSlotPushesTheOthersDown() {
		RouteView a = routes.create(user, inSlot(pettahBorella, 1));
		RouteView b = routes.create(user, inSlot(townHallThummulla, 2));
		clock.advance(Duration.ofDays(2));

		RouteView c = routes.create(user, inSlot(kollupitiyaBambalapitiya, 1));
		assertThat(slots(routes.list(user))).isEqualTo(Map.of(c.id(), 1, a.id(), 2, b.id(), 3));
	}

	@Test
	void pushingARouteIntoALockedSlotIsRefused() {
		routes.create(user, inSlot(pettahBorella, 1));
		clock.advance(Duration.ofDays(2));
		routes.create(user, inSlot(townHallThummulla, 2)); // slot 2 now locked
		clock.advance(Duration.ofHours(1));

		// A new #1 would push the old #1 into the locked #2
		assertThat(refusal(() -> routes.create(user, inSlot(kollupitiyaBambalapitiya, 1)))).containsExactly(Code.SLOT_LOCKED);
	}

	@Test
	void threeRoutesFillEverySlot() {
		routes.create(user, inSlot(pettahBorella, 1));
		routes.create(user, inSlot(townHallThummulla, 2));
		routes.create(user, inSlot(kollupitiyaBambalapitiya, 3));
		clock.advance(Duration.ofDays(2));
		assertThat(refusal(() -> routes.create(user, inSlot(thummullaHavelock, 1)))).contains(Code.SLOTS_FULL);
	}

	@Test
	void reorderingLocksTheSlotsThatGetANewRoute() {
		RouteView a = routes.create(user, inSlot(pettahBorella, 1));
		RouteView b = routes.create(user, inSlot(townHallThummulla, 2));
		RouteView c = routes.create(user, inSlot(kollupitiyaBambalapitiya, 3));
		clock.advance(Duration.ofDays(2));

		MyRoutes after = routes.reorder(user, Map.of(a.id(), 2, b.id(), 1, c.id(), 3));
		assertThat(slots(after)).isEqualTo(Map.of(b.id(), 1, a.id(), 2, c.id(), 3));
		assertThat(after.slots().get(0).lockedUntil()).isNotNull();
		assertThat(after.slots().get(1).lockedUntil()).isNotNull();
		assertThat(after.slots().get(2).lockedUntil()).isNull(); // #3 kept its route

		clock.advance(Duration.ofHours(1));
		assertThat(refusal(() -> routes.reorder(user, Map.of(a.id(), 1, b.id(), 2, c.id(), 3))))
			.containsExactly(Code.SLOT_LOCKED, Code.SLOT_LOCKED);
	}

	@Test
	void savesFromOneAccountRunOneAtATime() throws Exception {
		// Two overlapping routes saved at the same moment into different slots: the user lock makes the second
		// save see the first, so exactly one succeeds
		CountDownLatch start = new CountDownLatch(1);
		Callable<Boolean> saveA = () -> save(start, inSlot(kollupitiyaBambalapitiya, 1));
		Callable<Boolean> saveB = () -> save(start, inSlot(fortKollupitiya, 2));
		ExecutorService pool = Executors.newFixedThreadPool(2);
		List<Future<Boolean>> results = new ArrayList<>(List.of(pool.submit(saveA), pool.submit(saveB)));
		start.countDown();
		int saved = 0;
		for (Future<Boolean> result : results) {
			saved += result.get() ? 1 : 0;
		}
		pool.shutdown();
		assertThat(saved).isEqualTo(1);
		assertThat(routes.list(user).routes()).hasSize(1);
	}

	@Test
	void bannedAccountsCannotChangeRoutes() {
		jdbc.sql("UPDATE app_user SET banned_at = now() WHERE id = :id").param("id", user).update();
		assertThatThrownBy(() -> routes.create(user, inSlot(pettahBorella, 1)))
			.hasMessageContaining("401");
	}

	private boolean save(CountDownLatch start, RouteInput input) throws InterruptedException {
		start.await();
		try {
			routes.create(user, input);
			return true;
		}
		catch (RouteRefusedException e) {
			assertThat(e.problems()).extracting(Problem::code).containsExactly(Code.OVERLAP);
			return false;
		}
	}

	private static Map<Long, Integer> slots(MyRoutes mine) {
		Map<Long, Integer> slots = new java.util.HashMap<>();
		mine.routes().forEach(r -> slots.put(r.id(), r.slot()));
		return slots;
	}

}
