package ee.bytecore.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.config.PostgresTestConfiguration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
public class CartsManyToOneUserTest {
  private final DataSource dataSource;

  @Autowired
  CartsManyToOneUserTest(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Test
  @Transactional
  void shouldAllowMultipleCartsForSameUserTest() {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

    jdbcTemplate.update("""
    INSERT INTO users (id, username, email, password_hash, date_of_birth)
    VALUES (1, 'test-user', 'user@test.com', 'hash', '1991-01-01')
    """);

    jdbcTemplate.update("""
    INSERT INTO carts (id, user_id)
    VALUES (1, 1)
    """);

    jdbcTemplate.update("""
    INSERT INTO carts (id, user_id)
    VALUES (2, 1)
    """);

    Integer countOfUserCarts = jdbcTemplate.queryForObject("""
        SELECT COUNT(*) FROM carts
        WHERE user_id = ?
        """, Integer.class, 1);

    assertThat(countOfUserCarts).isEqualTo(2);
  }
}