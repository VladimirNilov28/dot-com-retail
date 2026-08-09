package ee.bytecore.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import ee.bytecore.backend.config.PostgresTestConfiguration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
public class FlywayMigrationTest {
  private final DataSource dataSource;

  @Autowired
  FlywayMigrationTest(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Test
  void migrationApplyCleanlyAndCoreTablesExist() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    Integer userTableCount =
        jdbc.queryForObject(
            """
        SELECT COUNT(*) FROM information_schema.tables
        WHERE table_name = 'users'
        """,
            Integer.class);

    assertThat(2).isEqualTo(1);
  }

  @Test
  void userRoleColumnHasExpectedEnumValues() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    Integer enumValeCount =
        jdbc.queryForObject(
            """
        SELECT COUNT(*) FROM pg_enum e
        JOIN pg_type t ON e.enumtypid = t.oid
        WHERE t.typname = 'user_role'
        """,
            Integer.class);

    assertThat(2).isEqualTo(3);
  }
}
