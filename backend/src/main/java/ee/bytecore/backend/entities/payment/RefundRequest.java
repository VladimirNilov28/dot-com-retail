package ee.bytecore.backend.entities.payment;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Customer-initiated refund request awaiting a staff decision (V19). */
@Getter
@Setter
@Entity
@Table(name = "refund_requests")
public class RefundRequest {
    protected RefundRequest() {}

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private String requestedBy;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Column(nullable = false)
    private String status;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decision_note")
    private String decisionNote;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "refund_id")
    private UUID refundId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static RefundRequest open(Long orderId, String requestedBy, String reason) {
        RefundRequest request = new RefundRequest();
        request.id = UUID.randomUUID();
        request.orderId = orderId;
        request.requestedBy = requestedBy;
        request.reason = reason;
        request.status = "REQUESTED";
        request.createdAt = Instant.now();
        request.updatedAt = request.createdAt;
        return request;
    }
}
