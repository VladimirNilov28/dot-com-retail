package ee.bytecore.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.integration.payment.PaymentOutboxPublisher;
import ee.bytecore.backend.repositories.user.UserRepository;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@SpringBootTest(properties = {"spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.listener.auto-startup=false"})
@Import(PostgresTestConfiguration.class)
@Tag("integration")
class GuestCartSchemaTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    @MockitoBean
    PaymentOutboxPublisher outboxPublisher;

    @Test
    @Transactional
    void shouldPersistGuestWithoutUserAndCascadeItemsOnDeletionTest() {
        Long cartId = jdbc.queryForObject(
                """
                INSERT INTO carts(guest_credential_hash,guest_expires_at)
                VALUES (?,NOW()+INTERVAL '30 days') RETURNING id
                """,
                Long.class,
                hash());
        Long productId = jdbc.queryForObject(
                "INSERT INTO products(name,slug) VALUES ('Guest schema',?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString());
        Long variantId = jdbc.queryForObject(
                """
                INSERT INTO product_variants(product_id,sku,price)
                VALUES (?,?,9.99) RETURNING id
                """,
                Long.class,
                productId,
                UUID.randomUUID().toString());
        jdbc.update("INSERT INTO cart_items(cart_id,product_variant_id,quantity) VALUES (?,?,2)", cartId, variantId);
        assertThat(jdbc.queryForObject("SELECT user_id FROM carts WHERE id=?", Long.class, cartId))
                .isNull();
        jdbc.update("DELETE FROM carts WHERE id=?", cartId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cart_items WHERE cart_id=?", Integer.class, cartId))
                .isZero();
    }

    @Test
    @Transactional
    void shouldRejectCartWithBothGuestCredentialAndAuthenticatedOwnerTest() {
        Long owner = userId();
        assertThatThrownBy(() -> jdbc.update(
                        """
                INSERT INTO carts(user_id,guest_credential_hash,guest_expires_at)
                VALUES (?,?,NOW()+INTERVAL '30 days')
                """,
                        owner,
                        hash()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void shouldRejectGuestCredentialWithoutExpiryTest() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO carts(guest_credential_hash) VALUES (?)", hash()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void shouldRejectGuestExpiryWithoutCredentialTest() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO carts(guest_expires_at) VALUES (NOW()+INTERVAL '1 day')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void shouldRejectDuplicateGuestCredentialHashTest() {
        String hash = hash();
        jdbc.update(
                """
                INSERT INTO carts(guest_credential_hash,guest_expires_at)
                VALUES (?,NOW()+INTERVAL '30 days')
                """,
                hash);
        assertThatThrownBy(() -> jdbc.update(
                        """
                INSERT INTO carts(guest_credential_hash,guest_expires_at)
                VALUES (?,NOW()+INTERVAL '30 days')
                """,
                        hash))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void shouldPreserveOneCartPerAuthenticatedOwnerTest() {
        Long owner = userId();
        jdbc.update("INSERT INTO carts(user_id) VALUES (?)", owner);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO carts(user_id) VALUES (?)", owner))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Long userId() {
        String suffix = UUID.randomUUID().toString();
        return users.save(User.create("guest-schema-" + suffix, suffix + "@example.com", LocalDate.of(1990, 1, 1)))
                .getId();
    }

    private String hash() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }
}
