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
public class CascadeDeleteTest {
    private final DataSource dataSource;

    @Autowired
    CascadeDeleteTest(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Test
    @Transactional
    void onCascadeDeleteUserAddressTest() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        jdbcTemplate.update(
                """
      INSERT INTO users (id, role, username, email, password_hash, date_of_birth)
      VALUES (1, 'USER', 'username', 'user@test.com', 'hash', '1999-01-01')
      """);

        jdbcTemplate.update(
                """
      INSERT INTO user_address (id, user_id, first_name, last_name, city, country, postal_code, address_line1, address_line2, mobile)
      VALUES (1, 1, 'test', 'test', 'testCity', 'testCountry', '00001', 'testAddress 111-12', 'testAddress 222-21', '+3725555999')
      """);

        jdbcTemplate.update("""
      DELETE FROM users
      WHERE id = ?
      """, 1);

        Integer userAddressCount = jdbcTemplate.queryForObject(
                """
          SELECT COUNT(*) FROM user_address
          """, Integer.class);

        assertThat(userAddressCount).isEqualTo(0);
    }

    @Test
    @Transactional
    void onCascadeDeleteCartItemsTest() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        jdbcTemplate.update(
                """
        INSERT INTO users (id, role, username, email, password_hash, date_of_birth)
        VALUES (1, 'USER', 'username', 'user@test.com', 'hash', '1991-01-01')
        """);

        jdbcTemplate.update("""
        INSERT INTO carts (id, user_id)
        VALUES (1, 1)
        """);

        jdbcTemplate.update(
                """
        INSERT INTO products (id, name, slug)
        VALUES (1, 'Test Product', 'test-product')
        """);

        jdbcTemplate.update(
                """
        INSERT INTO product_variants (id, product_id, price, sku)
        VALUES (1, 1, 9.99, 'test123')
        """);

        jdbcTemplate.update(
                """
        INSERT INTO cart_items (cart_id, product_variant_id, quantity)
        VALUES (1, 1, 5)
        """);

        jdbcTemplate.update("""
        DELETE FROM carts
        WHERE id = ?
        """, 1);

        Long cartItemsCount = jdbcTemplate.queryForObject(
                """
            SELECT COUNT(*) FROM cart_items WHERE cart_id = ?
            """, Long.class, 1);

        assertThat(cartItemsCount).isEqualTo(0L);
    }

    @Test
    @Transactional
    void onCascadeDeleteOrderItemsTest() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        jdbcTemplate.update(
                """
        INSERT INTO users (id, role, username, email, password_hash, date_of_birth)
        VALUES (1, 'USER', 'username', 'user@test.com', 'hash', '1991-01-01')
        """);

        jdbcTemplate.update(
                """
        INSERT INTO products (id, name, slug)
        VALUES (1, 'Test Product', 'test-product')
        """);

        jdbcTemplate.update(
                """
        INSERT INTO product_variants (id, product_id, price, sku)
        VALUES (1, 1, 9.99, 'test-sku')
        """);

        jdbcTemplate.update(
                """
        INSERT INTO orders (id, user_id, total_amount)
        VALUES (1, 1, 49.95)
        """);

        jdbcTemplate.update(
                """
        INSERT INTO order_items (order_id, product_variant_id, quantity, price_at_purchase)
        VALUES (1, 1, 5, 9.99)
        """);

        jdbcTemplate.update("""
        DELETE FROM orders
        WHERE id = ?
        """, 1);

        Long orderItemsCount = jdbcTemplate.queryForObject(
                """
            SELECT COUNT(*) FROM order_items WHERE order_id = ?
            """, Long.class, 1);

        assertThat(orderItemsCount).isEqualTo(0L);
    }

    @Test
    @Transactional
    void onCascadeDeleteInventoryTest() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        jdbcTemplate.update(
                """
        INSERT INTO products (id, name, slug)
        VALUES (1, 'Test Product', 'test-product')
        """);

        jdbcTemplate.update(
                """
        INSERT INTO product_variants (id, product_id, price, sku)
        VALUES (1, 1, 9.99, 'test-sku')
        """);

        jdbcTemplate.update(
                """
        INSERT INTO warehouses (id, name)
        VALUES (1, 'Test Warehouse')
        """);

        jdbcTemplate.update(
                """
        INSERT INTO inventory (product_variant_id, warehouse_id, quantity)
        VALUES (1, 1, 100)
        """);

        jdbcTemplate.update("""
        DELETE FROM warehouses
        WHERE id = ?
        """, 1);

        Long inventoryCount = jdbcTemplate.queryForObject(
                """
            SELECT COUNT(*) FROM inventory WHERE warehouse_id = ?
            """, Long.class, 1);

        assertThat(inventoryCount).isEqualTo(0L);
    }
}
