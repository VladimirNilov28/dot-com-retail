package ee.bytecore.backend.migration;

import ee.bytecore.backend.config.PostgresTestConfiguration;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.function.Executable;
import org.springframework.transaction.annotation.Transactional;

import java.beans.Transient;

import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
public class UserSchemaTest {
  private DataSource dataSource;

  @Autowired
  UserSchemaTest(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Test
  @Transactional
  void inputWithNullEmailShouldBeRejectedTest() {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

    Executable inputWithNullEmail = () -> jdbcTemplate.update(""" 
    INSERT INTO users (role, username, email, password_hash, date_of_birth)
    VALUES ('user', 'testuser', NULL, 'hash', '2000-01-01')
    """);

    assertThrows(DataIntegrityViolationException.class, inputWithNullEmail);
  }

  @Test
  @Transactional
  void emailShouldBeUniqueTest() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    jdbc.update("""
    INSERT INTO users (role, username, email, password_hash, date_of_birth)
    VALUES ('user', 'testuser', 'user@test.com', 'hash', '2000-01-01')
    """);

    Executable inputSameEmail = () -> jdbc.update("""
    INSERT INTO users (role, username, email, password_hash, date_of_birth)
    VALUES ('user', 'testuser', 'user@test.com', 'hash', '2000-01-01')
    """);
    assertThrows(DuplicateKeyException.class, inputSameEmail);
  }

  @Test
  @Transactional
  void quantityLessThanZeroShouldBeRejectedTest() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    Executable inputNegativeQuantity = () -> jdbc.update("""
    INSERT INTO cart_items (quantity)
    VALUES (-12314)
    """);

    assertThrows(DataIntegrityViolationException.class, inputNegativeQuantity);
  }

  @Test
  @Transactional
  void uniqueCoupleOfWarehouseAndProductTest() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    jdbc.update("""
    INSERT INTO products (id, name)
    VALUES (1, 'Test Product');
    """);

    jdbc.update("""
    INSERT INTO product_variants (id, product_id, sku, price)
    VALUES (1, 1, 'SKU-1', 9.99);
    """);

    jdbc.update("""
    INSERT INTO warehouses (id, name)
    VALUES (1, 'Main Warehouse');
    """);

    jdbc.update("""
    INSERT INTO inventory (product_variant_id, warehouse_id, quantity)
    VALUES (1, 1, 10);
    """);

    Executable inputNotUniqueCouples = () -> jdbc.update("""
    INSERT INTO inventory (product_variant_id, warehouse_id, quantity)
    VALUES (1, 1, 23);
    """);

    assertThrows(DuplicateKeyException.class, inputNotUniqueCouples);
  }

  @Test
  @Transactional
  void userCanHaveOnlyOneWishlistTest() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    jdbc.update("""
    INSERT INTO users (id, role, username, email, password_hash, date_of_birth)
    VALUES (1, 'user', 'testuser', 'user@test.com', 'hash', '2000-01-01')
    """);

    jdbc.update("""
    INSERT INTO wishlists (id, user_id)
    VALUES (1, 1)
    """);

    Executable addSecondWishlistForSameUser = () -> jdbc.update("""
    INSERT INTO wishlists (id, user_id)
    VALUES (2, 1)
    """);

    assertThrows(DuplicateKeyException.class, addSecondWishlistForSameUser);
  }
}
