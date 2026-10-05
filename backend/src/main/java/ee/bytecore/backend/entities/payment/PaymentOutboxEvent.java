package ee.bytecore.backend.entities.payment;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * Transactional outbox row for events destined for the Payment Service.
 * Written in the same DB transaction as the domain change that requires it
 * (see {@code ee.bytecore.backend.integration.payment.KafkaPaymentEventPublisher}),
 * so the domain write and the outgoing-event write commit atomically. A
 * separate poller later publishes unpublished rows to Kafka and marks them
 * published - Kafka publication itself is never part of the domain
 * transaction.
 */
@Getter
@Setter
@Entity
@Table(name = "payment_outbox")
public class PaymentOutboxEvent {
    protected PaymentOutboxEvent() {}

    @Id
    @UuidGenerator(style = UuidGenerator.Style.RANDOM)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "published", nullable = false)
    private boolean published = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    public static PaymentOutboxEvent create(Long orderId, String eventType, String payload) {
        PaymentOutboxEvent event = new PaymentOutboxEvent();
        event.orderId = orderId;
        event.eventType = eventType;
        event.payload = payload;
        return event;
    }

    public void markPublished(Instant publishedAt) {
        this.published = true;
        this.publishedAt = publishedAt;
    }
}
