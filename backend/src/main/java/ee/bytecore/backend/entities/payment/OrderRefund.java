package ee.bytecore.backend.entities.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "order_refunds")
@Getter
@Setter
public class OrderRefund {
    @Id
    private UUID id;

    @Column(name = "request_event_id", nullable = false, updatable = false)
    private UUID requestEventId;

    @Column(name = "payment_request_event_id", nullable = false, updatable = false)
    private UUID paymentRequestEventId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "provider_transaction_id", updatable = false)
    private String providerTransactionId;

    @Column(nullable = false)
    private String status;

    @Column(name = "result_event_id")
    private UUID resultEventId;

    @Column(name = "refund_transaction_id")
    private String refundTransactionId;

    @Column(name = "failure_reason")
    private String failureReason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
