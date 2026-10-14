package ee.bytecore.backend.integration.payment.event;

import java.time.Instant;
import java.util.UUID;

public record RefundResultEvent(
        UUID eventId,
        UUID requestEventId,
        UUID refundId,
        Long orderId,
        UUID paymentId,
        String providerTransactionId,
        String reason,
        Instant occurredAt) {}
