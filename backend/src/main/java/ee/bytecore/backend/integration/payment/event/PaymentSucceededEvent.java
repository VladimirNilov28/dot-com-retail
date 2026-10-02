package ee.bytecore.backend.integration.payment.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Stable integration contract for a successful payment result from the
 * Payment Service (topic {@code payment.succeeded}). {@code requestEventId}
 * correlates back to the {@link PaymentRequestedEvent#eventId()} that
 * triggered this payment attempt; {@code paymentId} is the Payment Service's
 * own payment record id, useful for cross-service debugging.
 */
public record PaymentSucceededEvent(
        UUID eventId, UUID requestEventId, Long orderId, UUID paymentId, Instant occurredAt) {}
