package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.enums.UserRole;
import ee.bytecore.backend.exceptions.UserAlreadyExistsException;
import ee.bytecore.backend.integration.HydraClient;
import ee.bytecore.backend.integration.KratosClient;
import ee.bytecore.backend.integration.payment.PaymentOutboxPublisher;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@SpringBootTest(properties = {"spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.listener.auto-startup=false"})
@Import(PostgresTestConfiguration.class)
@Tag("integration")
class NativeRegistrationIntegrationTest {
    @Autowired
    UserService users;

    @MockitoBean
    KratosClient kratos;

    @MockitoBean
    HydraClient hydra;

    @MockitoBean
    PaymentOutboxPublisher outboxPublisher;

    @Test
    void shouldSerializeSameFlowAndFinalizeExactlyOneOrdinaryCustomerTest() throws Exception {
        UUID flow = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        String username = "native-" + suffix;
        String email = username + "@example.com";
        var birth = LocalDate.of(1990, 6, 1);
        var expiry = Instant.now().plusSeconds(600);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> users.reserveRegistration(flow, username, email, birth, expiry));
            var second = pool.submit(() -> users.reserveRegistration(flow, username, email, birth, expiry));
            var reservation = first.get(10, TimeUnit.SECONDS);
            assertThat(second.get(10, TimeUnit.SECONDS).getId()).isEqualTo(reservation.getId());
            assertThatThrownBy(() -> users.create(username, email, birth))
                    .isInstanceOf(UserAlreadyExistsException.class);
            assertThatThrownBy(() -> users.reserveRegistration(UUID.randomUUID(), username, email, birth, expiry))
                    .isInstanceOf(UserAlreadyExistsException.class);

            UUID identity = UUID.randomUUID();
            when(kratos.getNativeRegistrationIdentity(identity, true))
                    .thenReturn(new KratosClient.NativeRegistrationIdentity(
                            identity, reservation.getId(), username, email, birth, null));
            var finalizedFirst = pool.submit(() -> users.finalizeRegistration(identity));
            var finalizedSecond = pool.submit(() -> users.finalizeRegistration(identity));
            var customer = finalizedFirst.get(10, TimeUnit.SECONDS);
            assertThat(finalizedSecond.get(10, TimeUnit.SECONDS).getId()).isEqualTo(customer.getId());
            assertThat(customer.getKratosIdentityId()).isEqualTo(identity);
            assertThat(customer.getRole()).isEqualTo(UserRole.USER);
        }
    }

    @Test
    void shouldRejectSameFlowWithDifferentClaimWithoutStealingOriginalTest() {
        UUID flow = UUID.randomUUID();
        String username = "native-" + UUID.randomUUID();
        var birth = LocalDate.of(1990, 6, 1);
        var expiry = Instant.now().plusSeconds(600);
        var original = users.reserveRegistration(flow, username, username + "@example.com", birth, expiry);
        assertThatThrownBy(() -> users.reserveRegistration(
                        flow, username + "-changed", username + "-changed@example.com", birth, expiry))
                .isInstanceOf(UserAlreadyExistsException.class);
        assertThat(users.reserveRegistration(flow, username, username + "@example.com", birth, expiry)
                        .getId())
                .isEqualTo(original.getId());
    }
}
