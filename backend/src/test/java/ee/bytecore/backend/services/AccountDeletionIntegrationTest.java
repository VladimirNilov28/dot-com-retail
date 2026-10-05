package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.exceptions.IdentitySyncException;
import ee.bytecore.backend.exceptions.UserAlreadyExistsException;
import ee.bytecore.backend.integration.HydraClient;
import ee.bytecore.backend.integration.KratosClient;
import ee.bytecore.backend.integration.payment.PaymentOutboxPublisher;
import ee.bytecore.backend.repositories.user.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@SpringBootTest(properties = "spring.kafka.bootstrap-servers=127.0.0.1:1")
@Import({PostgresTestConfiguration.class, AccountDeletionIntegrationTest.NoKafka.class})
@Tag("integration")
class AccountDeletionIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class NoKafka {
        @Bean
        static BeanPostProcessor disableKafkaListeners() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String beanName) {
                    if (bean instanceof ConcurrentKafkaListenerContainerFactory<?, ?> factory) {
                        factory.setAutoStartup(false);
                    }
                    return bean;
                }
            };
        }
    }

    @Autowired
    UserService userService;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    KratosClient kratos;

    @MockitoBean
    HydraClient hydra;

    @MockitoBean
    PaymentOutboxPublisher outboxPublisher;

    @BeforeEach
    void linkIdentity() {
        when(kratos.findLinkedIdentity(any())).thenReturn(UUID.randomUUID());
    }

    @Test
    void shouldRetainOrdersAndOutboxButRemovePersonalDataTest() {
        User user = users.save(User.create("deleted-history", "deleted-history@example.com", LocalDate.of(1990, 2, 3)));
        Long orderId = jdbc.queryForObject(
                "INSERT INTO orders(user_id,total_amount) VALUES (?,12.34) RETURNING id", Long.class, user.getId());
        jdbc.update(
                "INSERT INTO payment_outbox(order_id,event_type,payload) VALUES (?,'PaymentRequested','{}')", orderId);
        jdbc.update("INSERT INTO user_address(user_id,first_name) VALUES (?,'Personal')", user.getId());
        jdbc.update(
                "INSERT INTO user_payment_methods(user_id,provider,type) VALUES (?,'Personal','CARD')", user.getId());
        jdbc.update("INSERT INTO carts(user_id) VALUES (?)", user.getId());
        jdbc.update("INSERT INTO wishlists(user_id) VALUES (?)", user.getId());
        Long productId = jdbc.queryForObject(
                "INSERT INTO products(name,slug) VALUES ('Retained','retained') RETURNING id", Long.class);
        Long variantId = jdbc.queryForObject(
                "INSERT INTO product_variants(product_id,sku,price) VALUES (?,'retained',12.34) RETURNING id",
                Long.class,
                productId);
        Long warehouseId =
                jdbc.queryForObject("INSERT INTO warehouses(name) VALUES ('Retained') RETURNING id", Long.class);
        Long inventoryId = jdbc.queryForObject(
                "INSERT INTO inventory(product_variant_id,warehouse_id) VALUES (?,?) RETURNING id",
                Long.class,
                variantId,
                warehouseId);
        jdbc.update(
                "INSERT INTO order_items(order_id,product_variant_id,inventory_id,quantity,price_at_purchase) VALUES (?,?,?,1,12.34)",
                orderId,
                variantId,
                inventoryId);
        jdbc.update(
                "INSERT INTO cart_items(cart_id,product_variant_id,quantity) SELECT id,?,1 FROM carts WHERE user_id=?",
                variantId,
                user.getId());
        jdbc.update(
                "INSERT INTO wishlist_items(wishlist_id,product_variant_id) SELECT id,? FROM wishlists WHERE user_id=?",
                variantId,
                user.getId());

        userService.deleteById(user.getId());
        userService.deleteById(user.getId());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE id=?", Integer.class, orderId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox WHERE order_id=?", Integer.class, orderId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT total_amount FROM orders WHERE id=?", java.math.BigDecimal.class, orderId))
                .isEqualByComparingTo("12.34");
        assertThat(jdbc.queryForObject("SELECT user_id FROM orders WHERE id=?", Long.class, orderId))
                .isEqualTo(user.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items WHERE order_id=?", Integer.class, orderId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, user.getId()))
                .isNotEqualTo(user.getEmail());
        for (String table : new String[] {"user_address", "user_payment_methods", "carts", "wishlists"}) {
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM " + table + " WHERE user_id=?", Integer.class, user.getId()))
                    .isZero();
        }
        assertThat(userService.findById(user.getId())).isEmpty();
        assertThat(users.findById(user.getId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT date_of_birth FROM users WHERE id=?", LocalDate.class, user.getId()))
                .isEqualTo(LocalDate.of(1970, 1, 1));
        assertThat(jdbc.queryForObject("SELECT role::text FROM users WHERE id=?", String.class, user.getId()))
                .isEqualTo("USER");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM cart_items WHERE product_variant_id=?", Integer.class, variantId))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM wishlist_items WHERE product_variant_id=?", Integer.class, variantId))
                .isZero();
        User replacement = userService.registerCustomer(
                user.getUsername(), user.getEmail(), "disposable-test-value", user.getDateOfBirth());
        assertThat(replacement.getId()).isNotEqualTo(user.getId());
        verify(kratos).createIdentity(user.getEmail(), "disposable-test-value", replacement.getId());
        assertThat(userService.findById(replacement.getId())).isPresent();
    }

    @Test
    void shouldRetainRetryCoordinateAfterRevocationThenDatabaseFailureTest() {
        User user = users.save(User.create("retry-db", "retry-db@example.com", LocalDate.of(1990, 2, 3)));
        jdbc.execute(
                """
                CREATE FUNCTION reject_anonymization() RETURNS trigger AS $$
                BEGIN
                    IF NEW.deleted THEN RAISE EXCEPTION 'injected database failure'; END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute(
                "CREATE TRIGGER reject_anonymization BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION reject_anonymization()");
        try {
            assertThatThrownBy(() -> userService.deleteById(user.getId())).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT deleted FROM users WHERE id=?", Boolean.class, user.getId()))
                    .isFalse();
            assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, user.getId()))
                    .isEqualTo(user.getEmail());
            assertThat(jdbc.queryForObject(
                            "SELECT deletion_identity_id FROM users WHERE id=?", UUID.class, user.getId()))
                    .isNotNull();
            verify(hydra).revokeUser(user.getId());
        } finally {
            jdbc.execute("DROP TRIGGER reject_anonymization ON users");
            jdbc.execute("DROP FUNCTION reject_anonymization()");
        }
        reset(kratos);
        userService.deleteById(user.getId());
        verify(kratos, org.mockito.Mockito.never()).findLinkedIdentity(any());
        assertThat(jdbc.queryForObject("SELECT deleted FROM users WHERE id=?", Boolean.class, user.getId()))
                .isTrue();
    }

    @Test
    void shouldNotCompleteWhenHydraFailsAndShouldSafelyRetryTest() {
        User user = users.save(User.create("retry-hydra", "retry-hydra@example.com", LocalDate.of(1990, 2, 3)));
        doThrow(new IdentitySyncException("Retry deletion")).when(hydra).revokeUser(user.getId());
        assertThatThrownBy(() -> userService.deleteById(user.getId())).isInstanceOf(IdentitySyncException.class);
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, user.getId()))
                .isEqualTo(user.getEmail());
        assertThat(users.findById(user.getId())).isEmpty();
        assertThatThrownBy(() -> userService.registerCustomer(
                        user.getUsername(), user.getEmail(), "disposable-test-value", user.getDateOfBirth()))
                .isInstanceOf(UserAlreadyExistsException.class);
        assertThatThrownBy(() -> userService.updateProfile(user.getId(), "new-name", "new@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> userService.updateRole(user.getId(), ee.bytecore.backend.enums.UserRole.ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
        reset(hydra, kratos);
        userService.deleteById(user.getId());
        verify(kratos, org.mockito.Mockito.never()).findLinkedIdentity(any());
        assertThat(jdbc.queryForObject("SELECT deleted FROM users WHERE id=?", Boolean.class, user.getId()))
                .isTrue();
    }

    @Test
    void shouldRollBackCanonicalRegistrationOnAuthenticationConflictTest() {
        org.mockito.Mockito.doThrow(new UserAlreadyExistsException("Use account recovery or contact support."))
                .when(kratos)
                .createIdentity(org.mockito.ArgumentMatchers.eq("orphan@example.com"), any(), any());
        assertThatThrownBy(() -> userService.registerCustomer(
                        "orphan", "orphan@example.com", "disposable-test-value", LocalDate.of(1990, 2, 3)))
                .isInstanceOf(UserAlreadyExistsException.class);
        assertThat(users.existsByEmail("orphan@example.com")).isFalse();
        assertThat(users.existsByUsername("orphan")).isFalse();
    }
}
