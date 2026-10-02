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
