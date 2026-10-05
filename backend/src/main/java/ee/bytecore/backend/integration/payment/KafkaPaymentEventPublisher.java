package ee.bytecore.backend.integration.payment;

import org.springframework.stereotype.Component;

import ee.bytecore.backend.entities.payment.PaymentOutboxEvent;
import ee.bytecore.backend.integration.payment.event.PaymentRequestedEvent;
import ee.bytecore.backend.integration.payment.event.RefundRequestedEvent;
import ee.bytecore.backend.repositories.payment.PaymentOutboxEventRepository;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Kafka-backed {@link PaymentEventPublisher}. Does not talk to Kafka
 * directly: publishing here means writing the event to the
 * {@code payment_outbox} table, in whatever DB transaction the caller is
 * already in (transactional outbox pattern). This avoids the classic
 * dual-write hazard (DB commit succeeds, Kafka send fails or vice versa) - a
 * separate poller (see {@code PaymentOutboxPublisher}) is the only component
 * that actually sends to Kafka, outside of any domain transaction.
 */
@Component
public class KafkaPaymentEventPublisher implements PaymentEventPublisher {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final PaymentOutboxEventRepository paymentOutboxEventRepository;

    public KafkaPaymentEventPublisher(PaymentOutboxEventRepository paymentOutboxEventRepository) {
        this.paymentOutboxEventRepository = paymentOutboxEventRepository;
    }

    @Override
    public void publishPaymentRequested(PaymentRequestedEvent event) {
        String payload;
        try {
            payload = OBJECT_MAPPER.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to serialize PaymentRequestedEvent for order " + event.orderId(), e);
        }
        paymentOutboxEventRepository.save(
                PaymentOutboxEvent.create(event.orderId(), PaymentTopics.PAYMENT_REQUESTED, payload));
    }

    @Override
    public void publishRefundRequested(RefundRequestedEvent event) {
        try {
            paymentOutboxEventRepository.save(PaymentOutboxEvent.create(
                    event.orderId(), PaymentTopics.REFUND_REQUESTED, OBJECT_MAPPER.writeValueAsString(event)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to serialize refund request for order " + event.orderId(), exception);
        }
    }
}
