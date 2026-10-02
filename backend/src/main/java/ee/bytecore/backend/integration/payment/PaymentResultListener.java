package ee.bytecore.backend.integration.payment;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import ee.bytecore.backend.enums.OrderStatus;
import ee.bytecore.backend.integration.payment.event.PaymentFailedEvent;
import ee.bytecore.backend.integration.payment.event.PaymentSucceededEvent;
import ee.bytecore.backend.services.OrderService;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Consumes payment result events from the Payment Service and applies them
 * through {@link OrderService} - the only component allowed to mutate Order
 * state, per the project's application/domain layering. Never touches Order
 * persistence directly from this Kafka infrastructure code.
 */
@Component
public class PaymentResultListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentResultListener.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final OrderService orderService;

    public PaymentResultListener(OrderService orderService) {
        this.orderService = orderService;
    }

    @KafkaListener(topics = PaymentTopics.PAYMENT_SUCCEEDED)
    public void onPaymentSucceeded(String payload) {
        PaymentSucceededEvent event = parse(payload, PaymentSucceededEvent.class);
        applyResult(event.orderId(), OrderStatus.PAID, null, event.eventId(), event.paymentId());
    }

    @KafkaListener(topics = PaymentTopics.PAYMENT_FAILED)
    public void onPaymentFailed(String payload) {
        PaymentFailedEvent event = parse(payload, PaymentFailedEvent.class);
        applyResult(
                event.orderId(),
                OrderStatus.CANCELLED,
                "PAYMENT_FAILED: " + event.reason(),
                event.eventId(),
                event.paymentId());
    }

    private <T> T parse(String payload, Class<T> type) {
        try {
            return OBJECT_MAPPER.readValue(payload, type);
        } catch (Exception e) {
            // Not a rejected-transition case - this is a genuinely malformed
            // message, so let it propagate to the container's error handler
            // (bounded retry, then DLT) rather than swallowing it here.
            throw new IllegalStateException("Malformed payment result event payload", e);
        }
    }

    private void applyResult(
            Long orderId, OrderStatus target, String cancellationReason, Object eventId, Object paymentId) {
        try {
            orderService.updateStatus(orderId, target, cancellationReason);
            log.info(
                    "Applied payment result eventId={} orderId={} paymentId={} status={}",
                    eventId,
                    orderId,
                    paymentId,
                    target);
        } catch (IllegalArgumentException e) {
            // The order can't currently transition to `target` - either this
            // is a duplicate/late redelivery of a result already applied, or
            // some other rejected transition. Either way it's a durable,
            // DB-state-backed idempotency check: retrying will never
            // succeed, so this must be treated as a safe no-op, not routed
            // to the DLT as if it were a malformed message.
            log.warn(
                    "Ignoring payment result that could not be applied (likely duplicate/late delivery) "
                            + "eventId={} orderId={} paymentId={} status={} reason={}",
                    eventId,
                    orderId,
                    paymentId,
                    target,
                    e.getMessage());
        }
    }
}
