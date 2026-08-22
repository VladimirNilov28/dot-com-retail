package ee.bytecore.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.config.PostgresTestConfiguration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Tag("integration")
public class OrdersSchemaTest {
  private final DataSource dataSource;

  @Autowired
  OrdersSchemaTest(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Test
  @Transactional
  void shouldSetCartIdNullWhenCartIsDeletedTest() {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

    jdbcTemplate.update(
        """
    INSERT INTO users (id, username, email, password_hash, date_of_birth)
    VALUES (1, 'test-user', 'user@test.com', 'hash', '1991-01-01')
    """);

    jdbcTemplate.update(
        """
    INSERT INTO carts (id, user_id)
    VALUES (1, 1)
    """);

    jdbcTemplate.update(
        """
    INSERT INTO orders (id, user_id, cart_id, total_amount)
    VALUES (1, 1, 1, 23)
    """);

    jdbcTemplate.update(
        """
    DELETE FROM carts
    WHERE id = ?
    """,
        1);

    String cartId =
        jdbcTemplate.queryForObject(
            """
        SELECT cart_id FROM orders
        WHERE id = ?
        """,
            String.class,
            1);

    assertThat(cartId).isNull();

    Integer ordersCount =
        jdbcTemplate.queryForObject(
            """
    SELECT COUNT(*) FROM orders
    WHERE id = ?
    """,
            Integer.class,
            1);

    assertThat(ordersCount).isEqualTo(1);
  }

  @Test
  @Transactional
  void shouldAllowOrderWithoutCartIdTest() {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

    jdbcTemplate.update(
        """
    INSERT INTO users (id, username, email, password_hash, date_of_birth)
    VALUES (1, 'test-user', 'user@test.com', 'hash', '1991-01-01')
    """);

    jdbcTemplate.update(
        """
    INSERT INTO orders (id, user_id, cart_id, total_amount)
    VALUES (2, 1, NULL, 50)
    """);

    Long cartId =
        jdbcTemplate.queryForObject(
            """
    SELECT cart_id FROM orders
    WHERE id = ?
    """,
            Long.class,
            2);

    assertThat(cartId).isNull();
  }
}
