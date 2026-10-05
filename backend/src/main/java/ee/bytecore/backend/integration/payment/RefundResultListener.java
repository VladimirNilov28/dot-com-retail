package ee.bytecore.backend.integration.payment;

import java.util.Objects;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.integration.payment.event.RefundResultEvent;
import ee.bytecore.backend.repositories.payment.OrderRefundRepository;
import ee.bytecore.backend.services.OrderService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class RefundResultListener {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Logger LOG = LoggerFactory.getLogger(RefundResultListener.class);
    private final OrderService orders;
    private final OrderRefundRepository refunds;

    public RefundResultListener(OrderService orders, OrderRefundRepository refunds) {
        this.orders = orders;
        this.refunds = refunds;
    }

    @KafkaListener(topics = PaymentTopics.REFUND_SUCCEEDED)
    @Transactional
    public void onRefundSucceeded(String payload) {
        apply(payload, "SUCCEEDED");
    }

    @KafkaListener(topics = PaymentTopics.REFUND_FAILED)
    @Transactional
    public void onRefundFailed(String payload) {
        apply(payload, "FAILED");
    }

    @KafkaListener(topics = PaymentTopics.REFUND_UNRESOLVED)
    @Transactional
    public void onRefundUnresolved(String payload) {
        apply(payload, "UNRESOLVED");
    }

    private void apply(String payload, String target) {
        RefundResultEvent event;
        try {
            event = JSON.readValue(payload, RefundResultEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Malformed refund result", exception);
        }
        if (event == null
                || event.eventId() == null
                || event.requestEventId() == null
                || event.refundId() == null
                || event.paymentId() == null
                || event.orderId() == null
                || event.orderId() <= 0
                || event.occurredAt() == null
                || (!"SUCCEEDED".equals(target)
                        && (event.reason() == null
                                || event.reason().isBlank()
                                || event.reason().length() > 255))
                || (event.providerTransactionId() != null
                        && (event.providerTransactionId().isBlank()
                                || event.providerTransactionId().length() > 255)))
            throw new IllegalStateException("Malformed refund result correlation or outcome");
        orders.lockForFinancialResult(event.orderId());
        var refund = refunds.findById(event.refundId());
        if (refund.isEmpty()
                || !Objects.equals(refund.get().getOrderId(), event.orderId())
                || !Objects.equals(refund.get().getPaymentId(), event.paymentId())
                || !Objects.equals(refund.get().getRequestEventId(), event.requestEventId())) {
            LOG.warn("Rejected uncorrelated refund result eventId={} refundId={}", event.eventId(), event.refundId());
            return;
        }
        var row = refund.get();
        if (java.util.Set.of("SUCCEEDED", "FAILED").contains(row.getStatus())) {
            if (!"UNRESOLVED".equals(target)
                    && (!row.getStatus().equals(target)
                            || !Objects.equals(row.getRefundTransactionId(), event.providerTransactionId())
                            || !Objects.equals(row.getFailureReason(), event.reason())))
                LOG.warn(
                        "Rejected conflicting terminal refund outcome eventId={} refundId={}",
                        event.eventId(),
                        event.refundId());
            return;
        }
        row.setStatus(target);
        row.setFailureReason("SUCCEEDED".equals(target) ? null : event.reason());
        if (!"UNRESOLVED".equals(target)) {
            row.setResultEventId(event.eventId());
            row.setRefundTransactionId(event.providerTransactionId());
        }
        refunds.saveAndFlush(row);
    }
}
