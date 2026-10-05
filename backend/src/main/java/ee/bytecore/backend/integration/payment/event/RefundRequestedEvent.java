package ee.bytecore.backend.integration.payment.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RefundRequestedEvent(
        UUID eventId,
        UUID refundId,
        UUID requestEventId,
        Long orderId,
        UUID paymentId,
        BigDecimal amount,
        String currency,
        String providerTransactionId,
        Instant occurredAt) {}
