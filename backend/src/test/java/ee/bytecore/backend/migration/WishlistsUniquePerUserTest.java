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
 * Wishlists must be one-per-user (enforced by the {@code UNIQUE} constraint
 * on {@code wishlists.user_id}, see V7__create_wishlist.sql) so that
 * concurrent lazy-wishlist-creation requests can't both win and leave a user
 * with two wishlists - mirrors {@link CartsUniquePerUserTest}.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Tag("integration")
public class WishlistsUniquePerUserTest {
    private final DataSource dataSource;

    @Autowired
    WishlistsUniquePerUserTest(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Test
    @Transactional
    void shouldRejectSecondWishlistForSameUserTest() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        // High explicit ids avoid colliding with auto-generated ids committed
        // by other, non-transactional integration tests sharing this
        // container/database within the same test run.
        jdbcTemplate.update(
                """
    INSERT INTO users (id, username, email, date_of_birth)
    VALUES (900101, 'unique-wishlist-test-user', 'unique-wishlist-test@test.com', '1991-01-01')
    """);

        jdbcTemplate.update("""
    INSERT INTO wishlists (id, user_id)
    VALUES (900101, 900101)
    """);

        // A failed insert aborts the surrounding Postgres transaction, so no
        // further statements run against this same @Transactional connection
        // afterwards - the thrown exception alone proves the constraint held.
        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
    INSERT INTO wishlists (id, user_id)
    VALUES (900102, 900101)
    """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
