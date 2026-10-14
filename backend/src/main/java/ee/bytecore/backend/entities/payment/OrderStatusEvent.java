package ee.bytecore.backend.entities.payment;

import java.time.Instant;

import jakarta.persistence.*;
import lombok.Getter;

/** Append-only order status history row (V19); written once per transition. */
@Getter
@Entity
@Table(name = "order_status_events")
public class OrderStatusEvent {
    protected OrderStatusEvent() {}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "from_status", updatable = false)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, updatable = false)
    private String toStatus;

    @Column(name = "actor_kind", nullable = false, updatable = false)
    private String actorKind;

    @Column(name = "note", updatable = false)
    private String note;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    public static OrderStatusEvent create(Long orderId, String from, String to, String actorKind, String note) {
        OrderStatusEvent event = new OrderStatusEvent();
        event.orderId = orderId;
        event.fromStatus = from;
        event.toStatus = to;
        event.actorKind = actorKind;
        event.note = note;
        event.occurredAt = Instant.now();
        return event;
    }
}
