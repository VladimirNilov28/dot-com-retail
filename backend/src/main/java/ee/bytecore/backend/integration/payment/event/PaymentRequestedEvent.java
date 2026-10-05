package ee.bytecore.backend.integration.payment.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Stable integration contract for the future Payment Service: emitted when the monolith
 * needs payment initiated for an order. Carries only data needed across the service
 * boundary, not the internal Order/OrderItem persistence shape.
 */
public record PaymentRequestedEvent(
        UUID eventId, Long orderId, Long userId, BigDecimal amount, String currency, Instant occurredAt) {}
