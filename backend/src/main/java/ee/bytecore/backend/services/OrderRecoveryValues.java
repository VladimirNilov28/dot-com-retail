package ee.bytecore.backend.services;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import ee.bytecore.backend.entities.payment.Order;
import ee.bytecore.backend.entities.payment.OrderRefund;
import ee.bytecore.backend.enums.OrderStatus;

public final class OrderRecoveryValues {
    private OrderRecoveryValues() {}

    public record Eligibility(boolean allowed, String reason) {}

    public record Cancellation(String source, Instant cancelledAt, boolean awaitingPaymentOutcome) {}

    public record Payment(String status) {}

    public record Refund(String status, BigDecimal amount, String currency, String failureReason) {}

    public record Summary(
            Eligibility cancellationEligibility, Cancellation cancellation, Payment payment, Refund refund) {}

    public record Payload(UUID requestId, SummaryOrder order) {}

    public record SummaryOrder(
            UUID publicId,
            String status,
            Eligibility cancellationEligibility,
            Cancellation cancellation,
            Payment payment,
            Refund refund) {}

    public static Eligibility eligibility(Order order) {
        return switch (order.getStatus()) {
            case PENDING, PAID -> new Eligibility(true, null);
            case SHIPPING -> new Eligibility(false, "PROCESSING_BEGUN");
            case COMPLETED -> new Eligibility(false, "COMPLETED");
            case CANCELLED -> new Eligibility(false, "ALREADY_CANCELLED");
        };
    }

    public static Summary summary(Order order, OrderRefund refund) {
        String paymentStatus = order.getPaymentStatus();
        return new Summary(
                eligibility(order),
                order.getStatus() == OrderStatus.CANCELLED
                        ? new Cancellation(
                                order.getCancellationSource(),
                                order.getCancelledAt(),
                                !java.util.Set.of("SUCCEEDED", "FAILED").contains(paymentStatus))
                        : null,
                new Payment(paymentStatus),
                refund == null
                        ? new Refund("NONE", null, null, null)
                        : new Refund(
                                refund.getStatus(),
                                refund.getAmount(),
                                refund.getCurrency(),
                                refund.getFailureReason()));
    }
}
