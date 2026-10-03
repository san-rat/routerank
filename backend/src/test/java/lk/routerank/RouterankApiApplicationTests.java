package lk.routerank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RouterankApiApplicationTests {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void migrationsCreateRoadTables() {
		Integer tables = jdbc.queryForObject("""
				SELECT count(*) FROM information_schema.tables
				WHERE table_name IN ('import_run', 'road_segment')""", Integer.class);
		assertThat(tables).isEqualTo(2);
	}

	@Test
	void migrationsCreateAppTables() {
		Integer tables = jdbc.queryForObject("""
				SELECT count(*) FROM information_schema.tables
				WHERE table_name IN ('app_user', 'bus_route', 'route', 'route_waypoint', 'route_segment',
				  'slot_change', 'segment_score', 'stretch', 'audit_log', 'fraud_flag', 'device_signal',
				  'spring_session', 'spring_session_attributes')""", Integer.class);
		assertThat(tables).isEqualTo(13);
	}

	@Test
	void auditLogIsAppendOnly() {
		jdbc.update("INSERT INTO audit_log (actor_id, action, target, reason) VALUES (1, 'ban', 'user:2', 'test')");
		assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET reason = 'changed'"))
			.hasMessageContaining("append-only");
		assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log")).hasMessageContaining("append-only");
	}

	@Test
	void oneLiveRoutePerSlot() {
		Long user = jdbc.queryForObject("""
				INSERT INTO app_user (google_sub, email, live_at)
				VALUES ('slot-test', 'slot@example.com', now() + interval '24 hours') RETURNING id""", Long.class);
		String insert = """
				INSERT INTO route (user_id, slot, kind, start_point, end_point, geom_out, geom_back,
				  length_out_m, length_back_m, removed_at)
				VALUES (?, 1, 'new', ST_Point(79.85, 6.93, 4326), ST_Point(79.86, 6.93, 4326),
				  ST_GeomFromText('LINESTRING(79.85 6.93, 79.86 6.93)', 4326),
				  ST_GeomFromText('LINESTRING(79.86 6.93, 79.85 6.93)', 4326), 1100, 1100, ?::timestamptz)""";

		jdbc.update(insert, user, "2026-10-01T00:00:00Z"); // removed routes don't hold the slot
		jdbc.update(insert, user, null);
		assertThatThrownBy(() -> jdbc.update(insert, user, null))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void roadSegmentRejectsInvalidOneway() {
		Long run = jdbc.queryForObject("""
				INSERT INTO import_run (extract_date, source_url)
				VALUES (now(), 'test') RETURNING id""", Long.class);
		String insert = """
				INSERT INTO road_segment (import_run_id, osm_way_id, from_node, to_node, part, road_class,
				  is_link, oneway, province, geom, length_m)
				VALUES (?, 1, 1, 2, 0, 'primary', false, ?, 'Western',
				  ST_GeomFromText('LINESTRING(79.85 6.93, 79.86 6.93)', 4326), 1100)""";

		assertThat(jdbc.update(insert, run, 1)).isEqualTo(1);
		assertThatThrownBy(() -> jdbc.update(insert.replace("1, 1, 2, 0", "2, 1, 2, 0"), run, 2))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

}
