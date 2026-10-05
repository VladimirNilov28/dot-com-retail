package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.entities.user.UserPaymentMethod;
import ee.bytecore.backend.enums.PaymentMethodType;
import ee.bytecore.backend.repositories.user.UserRepository;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A mocked UserPaymentMethodRepository never invokes Hibernate's Bean
 * Validation, so it can't demonstrate what actually happens when persistence
 * runs for real. Needs a real Postgres/Hibernate context — see
 * "Repository testing" in CLAUDE.md.
 */
@SpringBootTest(properties = {"spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.listener.auto-startup=false"})
@Import(PostgresTestConfiguration.class)
@Tag("integration")
class UserPaymentMethodServiceIntegrationTest {

    @Autowired
    UserRepository userRepository;

    @Autowired
    UserPaymentMethodService userPaymentMethodService;

    @Test
    @Transactional
    void shouldNotLeakHibernateValidationInternalsForOversizedProviderTest() {
        User user = userRepository.save(
                User.create("payment-method-test-user", "payment-method-test@example.com", LocalDate.of(1990, 1, 1)));
        String oversizedProvider = "x".repeat(300);
        UserPaymentMethod paymentMethod = UserPaymentMethod.create(user, oversizedProvider, PaymentMethodType.CARD);

        assertThatThrownBy(() -> userPaymentMethodService.create(paymentMethod))
                .satisfies(error -> assertThat(error.getMessage())
                        .as("an oversized provider must not leak Hibernate/Jakarta Validation internals")
                        .doesNotContain("ConstraintViolationException")
                        .doesNotContain("jakarta.validation")
                        .doesNotContain("ConstraintViolationImpl"));
    }
}
