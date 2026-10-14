package ee.bytecore.backend.integration.payment.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A definite, non-terminal decline of one provider attempt. The payment stays retryable on the
 * same order; {@code attemptId} deduplicates per attempt and {@code reason} is an allow-listed code.
 */
public record PaymentDeclinedEvent(
        UUID eventId,
        UUID requestEventId,
        Long orderId,
        UUID paymentId,
        String attemptId,
        String reason,
        Instant occurredAt) {}
