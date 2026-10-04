package lk.routerank.scoring;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import lk.routerank.scoring.Rankings.Named;
import lk.routerank.scoring.Rankings.Result;
import lk.routerank.scoring.StretchBuilder.Group;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The 30-minute scoring job (see the Architecture doc): points per segment → named stretches → ranks → files on
 * R2. Runs at :00 and :30 UTC, and when an admin asks.
 *
 * <ul>
 * <li>A Postgres advisory lock (held on its own connection for the whole run) means two runs never overlap,
 * even with more than one API instance.</li>
 * <li>Scoring reads one REPEATABLE READ snapshot, so a save during the run is either all in or all out.</li>
 * <li>Publishing uploads only files whose content changed, then the manifest, within a monthly write budget.</li>
 * </ul>
 */
@Service
public class ScoringJob {

	private static final Logger log = LoggerFactory.getLogger(ScoringJob.class);

	/** Any constant: the advisory lock's key ("rr-score" in ASCII). */
	static final long LOCK_KEY = 0x72722d73636f7265L;

	static final String IMMUTABLE = "public, max-age=31536000, immutable";

	/** r2.dev can't purge a cache, so the manifest is cached briefly: rankings show within ~31 minutes. */
	static final String MANIFEST_CACHE = "public, max-age=60";

	private final ScoreStore store;

	private final DataSource dataSource;

	private final TransactionTemplate snapshot;

	private final ObjectStore objects;

	private final ScoringProperties props;

	private final Clock clock;

	private final JsonMapper json;

	ScoringJob(ScoreStore store, DataSource dataSource, PlatformTransactionManager transactions, ObjectStore objects,
			ScoringProperties props, Clock clock, JsonMapper json) {
		this.store = store;
		this.dataSource = dataSource;
		this.snapshot = new TransactionTemplate(transactions);
		this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
		this.objects = objects;
		this.props = props;
		this.clock = clock;
		this.json = json;
	}

	/**
	 * What one run did.
	 *
	 * @param at when it read the votes (the manifest's {@code generatedAt})
	 * @param segments segments with points
	 * @param uploaded files uploaded, the manifest included (0 when nothing was published)
	 * @param published whether the manifest now points at this run's files
	 * @param note why it didn't publish, when it didn't
	 */
	public record Run(Instant at, int segments, int stretches, int ranked, int uploaded, int deleted, boolean published,
			String note) {
	}

	@Scheduled(cron = "${routerank.scoring.schedule:0 0,30 * * * *}", zone = "UTC")
	void scheduled() {
		try {
			runNow().ifPresentOrElse(run -> log.info("Scoring run: {}", run),
					() -> log.info("Scoring run skipped: another run is in progress"));
		}
		catch (RuntimeException e) {
			log.error("Scoring run failed", e);
		}
	}

	/** Runs the job now, or returns empty when another run holds the lock. */
	public Optional<Run> runNow() {
		try (Connection lock = dataSource.getConnection()) {
			if (!advisory(lock, "SELECT pg_try_advisory_lock(?)")) {
				return Optional.empty();
			}
			try {
				return Optional.of(run());
			}
			finally {
				advisory(lock, "SELECT pg_advisory_unlock(?)");
			}
		}
		catch (SQLException e) {
			throw new IllegalStateException("Scoring lock failed", e);
		}
	}

	private static boolean advisory(Connection connection, String sql) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, LOCK_KEY);
			try (ResultSet rs = statement.executeQuery()) {
				return rs.next() && rs.getBoolean(1);
			}
		}
	}

	private record Scored(int segments, Result result, List<String> provinces, Map<String, Integer> people) {
	}

	private Run run() {
		Instant now = clock.instant();
		Scored scored = snapshot.execute(status -> score(now));
		if (scored == null) {
			return new Run(now, 0, 0, 0, 0, 0, false, "no road import");
		}
		List<Rankings.Stretch> stretches = scored.result().stretches();
		int ranked = (int) stretches.stream().filter(Rankings.Stretch::ranked).count();
		if (objects == ObjectStore.NONE) {
			return new Run(now, scored.segments(), stretches.size(), ranked, 0, 0, false, "publishing is off");
		}
		Publication publication = Publication.build(stretches, scored.result().slugs(), scored.provinces(),
				scored.people(), now, json);
		return publish(publication, now, scored.segments(), stretches.size(), ranked);
	}

	private Scored score(Instant now) {
		Optional<Long> importRun = store.currentImport();
		if (importRun.isEmpty()) {
			return null;
		}
		long run = importRun.get();
		List<ScoredSegment> segments = store.scoredSegments(run, now);
		List<long[]> parallel = store.parallelPairs(segments);
		Coverage coverage = new Coverage(StretchBuilder.build(segments, parallel), parallel);
		store.countedRoutes(now, coverage::add);
		List<Group> groups = coverage.counted();

		// Name each stretch from the nearest place to each end, the end nearer Colombo first
		List<ScoredSegment.Node> ends = new ArrayList<>();
		for (Group g : groups) {
			ScoredSegment.Node[] pair = StretchBuilder.ends(g);
			boolean swap = StretchBuilder.distanceM(pair[1], Naming.COLOMBO) < StretchBuilder.distanceM(pair[0],
					Naming.COLOMBO);
			ends.add(swap ? pair[1] : pair[0]);
			ends.add(swap ? pair[0] : pair[1]);
		}
		List<String> places = store.nearestPlaces(run, ends);
		List<Named> named = new ArrayList<>();
		for (int i = 0; i < groups.size(); i++) {
			Group g = groups.get(i);
			ScoredSegment road = g.road();
			String roadName = Naming.road(road.name(), road.ref());
			ScoredSegment.Node near = ends.get(2 * i);
			ScoredSegment.Node far = ends.get(2 * i + 1);
			named.add(new Named(g, Naming.name(places.get(2 * i), places.get(2 * i + 1), roadName), roadName,
					new double[][] { { near.lon(), near.lat() }, { far.lon(), far.lat() } }));
		}
		named = Naming.distinct(named);

		Result result = Rankings.rank(named, store.links());
		store.save(segments, result.stretches(), result.newLinks(), now);
		return new Scored(segments.size(), result, store.provinces(run), store.peopleByProvince(run, now));
	}

	private Run publish(Publication publication, Instant now, int segments, int stretches, int ranked) {
		ScoringProperties.Publish p = props.publish();
		Set<String> fresh = new HashSet<>(publication.files().keySet());
		fresh.removeAll(store.published(publication.files().keySet()));
		int needed = fresh.size() + 1;
		int used = store.writes(now);
		if (used + needed > p.monthlyWriteBudget()) {
			log.warn("Not publishing: {} R2 writes this month and this run needs {} more (budget {})", used, needed,
					p.monthlyWriteBudget());
			return new Run(now, segments, stretches, ranked, 0, 0, false, "monthly write budget reached");
		}
		int uploaded = 0;
		for (var file : publication.files().entrySet()) {
			if (fresh.contains(file.getKey())) {
				objects.put(file.getKey(), file.getValue(), "application/json", IMMUTABLE);
				store.addWrites(now, 1);
				store.recordUpload(file.getKey(), now);
				uploaded++;
			}
		}
		objects.put(Publication.MANIFEST, publication.manifest(), "application/json", MANIFEST_CACHE);
		store.addWrites(now, 1);
		uploaded++;
		store.markReferenced(publication.files().keySet(), now);

		// Deletes are free; files the manifest stopped naming 2 days ago are no longer read by anyone
		int deleted = 0;
		for (String key : store.unreferencedSince(now.minus(p.retention()))) {
			objects.delete(key);
			store.forget(key);
			deleted++;
		}
		return new Run(now, segments, stretches, ranked, uploaded, deleted, true, null);
	}

}
