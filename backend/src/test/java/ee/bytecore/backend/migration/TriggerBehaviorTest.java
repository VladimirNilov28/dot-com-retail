package ee.bytecore.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import ee.bytecore.backend.config.PostgresTestConfiguration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Tag("integration")
class TriggerBehaviorTest {

    private final DataSource dataSource;

    @Autowired
    TriggerBehaviorTest(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @ParameterizedTest(name = "{0} updated_at should change after UPDATE")
    @MethodSource("tablesWithUpdatedAtTrigger")
    void shouldUpdateUpdatedAtWhenEntityIsUpdatedTest(
            String tableName,
            String prepareSql,
            String selectUpdatedAtSql,
            String updateSql,
            String cleanupSql,
            Long id) {

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        // Defensive cleanup before the test runs, in case a previous failed run
        // left leftover data behind. Safe to run even if there is nothing to delete.
        jdbcTemplate.execute(cleanupSql);

        try {
            jdbcTemplate.execute(prepareSql);

            OffsetDateTime updatedAtOnCreate =
                    jdbcTemplate.queryForObject(selectUpdatedAtSql, OffsetDateTime.class, id);

            // Capture the moment right before the UPDATE from the application side.
            // More reliable than Thread.sleep and does not depend on being inside
            // a single transaction (see note above on why @Transactional cannot be used here:
            // now()/CURRENT_TIMESTAMP is fixed to transaction start in Postgres).
            OffsetDateTime beforeUpdate = OffsetDateTime.now();

            jdbcTemplate.update(updateSql, id);

            OffsetDateTime updatedAtOnUpdate =
                    jdbcTemplate.queryForObject(selectUpdatedAtSql, OffsetDateTime.class, id);

            assertThat(updatedAtOnUpdate)
                    .as("updated_at should change and be later than the original value")
                    .isAfter(updatedAtOnCreate);

            assertThat(updatedAtOnUpdate)
                    .as("updated_at should not be earlier than when the UPDATE was issued")
                    .isAfterOrEqualTo(beforeUpdate.minusSeconds(1)); // small buffer for clock drift between app and DB

        } finally {
            jdbcTemplate.execute(cleanupSql);
        }
    }

    static Stream<Arguments> tablesWithUpdatedAtTrigger() {
        return Stream.of(
                Arguments.of(
                        "users",
                        """
            INSERT INTO users
            (id, username, email, password_hash, date_of_birth)
            VALUES (1, 'testuser', 'user@test.com', 'hash', '1991-01-01')
            """,
                        """
            SELECT updated_at
            FROM users
            WHERE id = ?
            """,
                        """
            UPDATE users
            SET username = 'updated-user'
            WHERE id = ?
            """,
                        """
            DELETE FROM users
            WHERE id = 1
            """,
                        1L),
                Arguments.of(
                        "products",
                        """
            INSERT INTO products
            (id, name, slug, description)
            VALUES (1, 'product', 'product', 'description')
            """,
                        """
            SELECT updated_at
            FROM products
            WHERE id = ?
            """,
                        """
            UPDATE products
            SET name = 'updated-product'
            WHERE id = ?
            """,
                        """
            DELETE FROM products
            WHERE id = 1
            """,
                        1L),
                Arguments.of(
                        "product_variants",
                        """
            INSERT INTO products
            (id, name, slug)
            VALUES (1, 'product', 'product');

            INSERT INTO product_variants
            (id, product_id, sku, price)
            VALUES (1, 1, 'SKU-1', 100)
            """,
                        """
            SELECT updated_at
            FROM product_variants
            WHERE id = ?
            """,
                        """
            UPDATE product_variants
            SET price = 200
            WHERE id = ?
            """,
                        """
            DELETE FROM product_variants WHERE id = 1;
            DELETE FROM products WHERE id = 1
            """,
                        1L),
                Arguments.of(
                        "carts",
                        """
            INSERT INTO users
            (id, username, email, password_hash, date_of_birth)
            VALUES (1, 'cart-user', 'cart@test.com', 'hash', '1991-01-01');

            INSERT INTO carts
            (id, user_id)
            VALUES (1, 1)
            """,
                        """
            SELECT updated_at
            FROM carts
            WHERE id = ?
            """,
                        """
            UPDATE carts
            SET user_id = 1
            WHERE id = ?
            """,
                        """
            DELETE FROM carts WHERE id = 1;
            DELETE FROM users WHERE id = 1
            """,
                        1L),
                Arguments.of(
                        "user_address",
                        """
            INSERT INTO users
            (id, username, email, password_hash, date_of_birth)
            VALUES (1, 'address-user', 'address@test.com', 'hash', '1991-01-01');

            INSERT INTO user_address
            (id, user_id, city)
            VALUES (1, 1, 'Tallinn')
            """,
                        """
            SELECT updated_at
            FROM user_address
            WHERE id = ?
            """,
                        """
            UPDATE user_address
            SET city = 'Tartu'
            WHERE id = ?
            """,
                        """
            DELETE FROM user_address WHERE id = 1;
            DELETE FROM users WHERE id = 1
            """,
                        1L),
                Arguments.of(
                        "user_payment_methods",
                        """
            INSERT INTO users
            (id, username, email, password_hash, date_of_birth)
            VALUES (1, 'payment-user', 'payment@test.com', 'hash', '1991-01-01');

            INSERT INTO user_payment_methods
            (id, user_id, provider, type)
            VALUES (1, 1, 'Visa', 'CARD')
            """,
                        """
            SELECT updated_at
            FROM user_payment_methods
            WHERE id = ?
            """,
                        """
            UPDATE user_payment_methods
            SET provider = 'Mastercard'
            WHERE id = ?
            """,
                        """
            DELETE FROM user_payment_methods WHERE id = 1;
            DELETE FROM users WHERE id = 1
            """,
                        1L),
                Arguments.of(
                        "categories",
                        """
            INSERT INTO categories
            (id, name, slug)
            VALUES (1, 'Electronics', 'electronics')
            """,
                        """
            SELECT updated_at
            FROM categories
            WHERE id = ?
            """,
                        """
            UPDATE categories
            SET name = 'Updated electronics'
            WHERE id = ?
            """,
                        """
            DELETE FROM categories
            WHERE id = 1
            """,
                        1L),
                Arguments.of(
                        "warehouses",
                        """
            INSERT INTO warehouses
            (id, name, location)
            VALUES (1, 'Main warehouse', 'Tallinn')
            """,
                        """
            SELECT updated_at
            FROM warehouses
            WHERE id = ?
            """,
                        """
            UPDATE warehouses
            SET location = 'Tartu'
            WHERE id = ?
            """,
                        """
            DELETE FROM warehouses
            WHERE id = 1
            """,
                        1L),
                Arguments.of(
                        "inventory",
                        """
            INSERT INTO products
            (id, name, slug)
            VALUES (1, 'product', 'product');

            INSERT INTO product_variants
            (id, product_id, sku, price)
            VALUES (1, 1, 'SKU-1', 100);

            INSERT INTO warehouses
            (id, name)
            VALUES (1, 'warehouse');

            INSERT INTO inventory
            (id, product_variant_id, warehouse_id, quantity)
            VALUES (1, 1, 1, 10)
            """,
                        """
            SELECT updated_at
            FROM inventory
            WHERE id = ?
            """,
                        """
            UPDATE inventory
            SET quantity = 20
            WHERE id = ?
            """,
                        """
            DELETE FROM inventory WHERE id = 1;
            DELETE FROM product_variants WHERE id = 1;
            DELETE FROM warehouses WHERE id = 1;
            DELETE FROM products WHERE id = 1
            """,
                        1L));
    }
}
