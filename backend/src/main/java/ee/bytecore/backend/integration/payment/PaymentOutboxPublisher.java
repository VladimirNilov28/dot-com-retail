package ee.bytecore.backend.integration.payment;

import java.time.Instant;
import java.util.List;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.entities.payment.PaymentOutboxEvent;
import ee.bytecore.backend.repositories.payment.PaymentOutboxEventRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls {@code payment_outbox} for unpublished rows and sends them to Kafka.
 * This is the only component that actually talks to Kafka on the outbound
 * side - it runs outside the domain transaction that created the row (see
 * {@link KafkaPaymentEventPublisher}), so a Kafka outage never blocks order
 * creation; unpublished rows are simply retried on the next poll.
 */
@Component
public class PaymentOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutboxPublisher.class);

    private final PaymentOutboxEventRepository paymentOutboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public PaymentOutboxPublisher(
            PaymentOutboxEventRepository paymentOutboxEventRepository, KafkaTemplate<String, String> kafkaTemplate) {
        this.paymentOutboxEventRepository = paymentOutboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 2000)
    public void publishUnpublished() {
        List<PaymentOutboxEvent> unpublished =
                paymentOutboxEventRepository.findAllByPublishedFalseOrderByCreatedAtAsc();
        for (PaymentOutboxEvent event : unpublished) {
            publish(event);
        }
    }

    /**
     * Each row is sent and marked published independently so one failing
     * send/row doesn't block the rest of the batch.
     */
    @Transactional
    void publish(PaymentOutboxEvent event) {
        try {
            // Key by orderId so all events for the same order land on the
            // same partition and are processed in order by the consumer.
            kafkaTemplate
                    .send(event.getEventType(), String.valueOf(event.getOrderId()), event.getPayload())
                    .get();
            event.markPublished(Instant.now());
            paymentOutboxEventRepository.save(event);
        } catch (Exception e) {
            log.warn(
                    "Failed to publish payment_outbox event id={} orderId={} topic={} - will retry on next poll",
                    event.getId(),
                    event.getOrderId(),
                    event.getEventType(),
                    e);
        }
    }
}
