package ee.bytecore.backend.integration.payment.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Stable integration contract for a failed payment result from the Payment
 * Service (topic {@code payment.failed}). {@code requestEventId} correlates
 * back to the {@link PaymentRequestedEvent#eventId()} that triggered this
 * payment attempt; {@code reason} is a short, safe-to-log decline
 * description (never card/PAN/CVV data - the Payment Service never has that
 * to begin with).
 */
public record PaymentFailedEvent(
        UUID eventId, UUID requestEventId, Long orderId, UUID paymentId, String reason, Instant occurredAt) {}
