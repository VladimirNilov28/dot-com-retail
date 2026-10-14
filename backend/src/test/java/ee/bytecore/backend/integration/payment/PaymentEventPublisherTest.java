package ee.bytecore.backend.integration.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import ee.bytecore.backend.integration.payment.event.PaymentRequestedEvent;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PaymentEventPublisherTest {

    @Test
    void eventContainsStableIntegrationDataTest() {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.now();

        PaymentRequestedEvent event =
                new PaymentRequestedEvent(eventId, 42L, 7L, new BigDecimal("39.98"), "EUR", occurredAt);

        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.orderId()).isEqualTo(42L);
        assertThat(event.userId()).isEqualTo(7L);
        assertThat(event.amount()).isEqualTo(new BigDecimal("39.98"));
        assertThat(event.currency()).isEqualTo("EUR");
        assertThat(event.occurredAt()).isEqualTo(occurredAt);
    }

    @Test
    void publisherCanBeMockedCleanlyTest() {
        PaymentEventPublisher publisher = mock(PaymentEventPublisher.class);
        PaymentRequestedEvent event =
                new PaymentRequestedEvent(UUID.randomUUID(), 1L, 1L, BigDecimal.TEN, "EUR", Instant.now());

        publisher.publishPaymentRequested(event);

        verify(publisher).publishPaymentRequested(event);
    }
}
