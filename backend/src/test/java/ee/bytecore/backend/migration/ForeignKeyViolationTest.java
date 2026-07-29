package ee.bytecore.backend.migration;

import static org.junit.jupiter.api.Assertions.assertThrows;

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
public class ForeignKeyViolationTest {
  private final DataSource dataSource;

  @Autowired
  ForeignKeyViolationTest(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Test
  @Transactional
  void shouldRejectNonexistentProductVariantTest() {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

    jdbcTemplate.update(
        """
        INSERT INTO users (id, role, username, email, password_hash, date_of_birth)
        VALUES (1, 'user', 'testuser', 'user@test.com', 'hash', '1900-01-01')
        """);

    jdbcTemplate.update(
        """
        INSERT INTO carts (id, user_id)
        VALUES (1, 1)
        """);

    Executable insertNonexistentProduct =
        () ->
            jdbcTemplate.update(
                """
        INSERT INTO cart_items (cart_id, product_variant_id, quantity)
        VALUES (1, 123, 5)
        """);

    assertThrows(DataIntegrityViolationException.class, insertNonexistentProduct);
  }
}