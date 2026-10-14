package ee.bytecore.backend.integration.payment.event;

import java.time.Instant;
import java.util.UUID;

public record PaymentUnresolvedEvent(
        UUID eventId, UUID requestEventId, Long orderId, UUID paymentId, String reason, Instant occurredAt) {}
