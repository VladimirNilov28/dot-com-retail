package ee.bytecore.backend.integration.payment;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ee.bytecore.backend.enums.OrderStatus;
import ee.bytecore.backend.integration.payment.event.PaymentFailedEvent;
import ee.bytecore.backend.integration.payment.event.PaymentRequestedEvent;
import ee.bytecore.backend.integration.payment.event.PaymentSucceededEvent;
import ee.bytecore.backend.repositories.payment.PaymentOutboxEventRepository;
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
    private final PaymentOutboxEventRepository paymentRequests;
    private final TransactionTemplate transaction;

    public PaymentResultListener(
            OrderService orderService,
            PaymentOutboxEventRepository paymentRequests,
            PlatformTransactionManager transactions) {
        this.orderService = orderService;
        this.paymentRequests = paymentRequests;
        this.transaction = new TransactionTemplate(transactions);
    }

    @KafkaListener(topics = PaymentTopics.PAYMENT_SUCCEEDED)
    public void onPaymentSucceeded(String payload) {
        PaymentSucceededEvent event = parse(payload, PaymentSucceededEvent.class);
        validateResult(event.eventId(), event.requestEventId(), event.orderId(), event.paymentId(), event.occurredAt());
        applyResult(
                event.orderId(), OrderStatus.PAID, null, event.eventId(), event.requestEventId(), event.paymentId());
    }

    @KafkaListener(topics = PaymentTopics.PAYMENT_FAILED)
    public void onPaymentFailed(String payload) {
        PaymentFailedEvent event = parse(payload, PaymentFailedEvent.class);
        validateResult(event.eventId(), event.requestEventId(), event.orderId(), event.paymentId(), event.occurredAt());
        if (event.reason() == null || event.reason().isBlank()) {
            throw new IllegalStateException("Malformed payment failure result: decline reason is required");
        }
        applyResult(
                event.orderId(),
                OrderStatus.CANCELLED,
                "PAYMENT_FAILED: " + event.reason(),
                event.eventId(),
                event.requestEventId(),
                event.paymentId());
    }

    private <T> T parse(String payload, Class<T> type) {
        try {
            T event = OBJECT_MAPPER.readValue(payload, type);
            if (event == null) throw new IllegalArgumentException("Event must not be null");
            return event;
        } catch (Exception e) {
            // Not a rejected-transition case - this is a genuinely malformed
            // message, so let it propagate to the container's error handler
            // (bounded retry, then DLT) rather than swallowing it here.
            throw new IllegalStateException("Malformed payment result event payload", e);
        }
    }

    private void validateResult(UUID eventId, UUID requestEventId, Long orderId, UUID paymentId, Instant occurredAt) {
        if (eventId == null
                || requestEventId == null
                || orderId == null
                || orderId <= 0
                || paymentId == null
                || occurredAt == null) {
            throw new IllegalStateException(
                    "Malformed payment result: required correlation fields are missing or invalid");
        }
    }

    private void applyResult(
            Long orderId,
            OrderStatus target,
            String cancellationReason,
            UUID eventId,
            UUID requestEventId,
            UUID paymentId) {
        try {
            transaction.executeWithoutResult(status -> {
                var persisted = paymentRequests
                        .findRequestForUpdate(requestEventId)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown payment requestEventId"));
                PaymentRequestedEvent requested = parse(persisted.getPayload(), PaymentRequestedEvent.class);
                if (!Objects.equals(persisted.getOrderId(), orderId)
                        || !Objects.equals(requested.orderId(), orderId)
                        || !Objects.equals(requested.eventId(), requestEventId)) {
                    throw new IllegalArgumentException("Payment result does not match its persisted request/order");
                }
                var receipt = paymentRequests.findResultByRequestEventId(requestEventId);
                if (receipt.isPresent()) {
                    var previous = receipt.get();
                    if (!Objects.equals(previous.getPaymentId(), paymentId)
                            || !Objects.equals(previous.getOrderId(), orderId)
                            || !Objects.equals(previous.getResultStatus(), target.name())) {
                        throw new IllegalArgumentException(
                                "Payment result conflicts with the previously recorded payment/outcome");
                    }
                    return;
                }
                if (paymentRequests.recordResult(
                                requestEventId, persisted.getId(), orderId, paymentId, eventId, target.name())
                        != 1) {
                    throw new IllegalArgumentException("Payment/result identity is already bound to another request");
                }
                orderService.updateStatus(orderId, target, cancellationReason);
            });
            log.info(
                    "Applied payment result eventId={} orderId={} paymentId={} status={}",
                    eventId,
                    orderId,
                    paymentId,
                    target);
        } catch (IllegalArgumentException e) {
            // Rejected correlation or late transitions roll back before this
            // catch, so neither a receipt nor an order/stock change can survive.
            log.warn(
                    "Ignoring rejected payment result " + "eventId={} orderId={} paymentId={} status={} reason={}",
                    eventId,
                    orderId,
                    paymentId,
                    target,
                    e.getMessage());
        }
    }
}
