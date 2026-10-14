package ee.bytecore.backend.migration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.config.PostgresTestConfiguration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Carts must be one-per-user (enforced by {@code uq_carts_user_id}) so that
 * concurrent lazy-cart-creation requests can't both win and leave a user with
 * two carts.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Tag("integration")
public class CartsUniquePerUserTest {
    private final DataSource dataSource;

    @Autowired
    CartsUniquePerUserTest(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Test
    @Transactional
    void shouldRejectSecondCartForSameUserTest() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        // High explicit ids avoid colliding with auto-generated ids committed
        // by other, non-transactional integration tests sharing this
        // container/database within the same test run.
        jdbcTemplate.update(
                """
    INSERT INTO users (id, username, email, date_of_birth)
    VALUES (900001, 'unique-cart-test-user', 'unique-cart-test@test.com', '1991-01-01')
    """);

        jdbcTemplate.update("""
    INSERT INTO carts (id, user_id)
    VALUES (900001, 900001)
    """);

        // A failed insert aborts the surrounding Postgres transaction, so no
        // further statements run against this same @Transactional connection
        // afterwards - the thrown exception alone proves the constraint held.
        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
    INSERT INTO carts (id, user_id)
    VALUES (900002, 900001)
    """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
