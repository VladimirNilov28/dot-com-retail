package ee.bytecore.backend.entities.payment;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;
import lombok.Getter;

@Entity
@Table(name = "checkout_requests")
@Getter
public class CheckoutRequest {
    protected CheckoutRequest() {}

    @Id
    @Column(name = "request_id", updatable = false)
    private UUID requestId;

    @Column(name = "user_id", updatable = false)
    private Long userId;

    @Column(name = "source_cart_id", nullable = false, updatable = false)
    private Long sourceCartId;

    @Column(name = "source_credential_hash", updatable = false)
    private String sourceCredentialHash;

    @Column(name = "guest_order_hash", updatable = false)
    private String guestOrderHash;

    @Column(name = "guest_expires_at", updatable = false)
    private Instant guestExpiresAt;

    @Column(name = "payload_hash", nullable = false, updatable = false)
    private String payloadHash;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true, updatable = false)
    private Order order;

    public static CheckoutRequest create(
            UUID requestId,
            Long userId,
            Long cartId,
            String sourceHash,
            String guestHash,
            Instant guestExpiresAt,
            String payloadHash,
            Order order) {
        CheckoutRequest request = new CheckoutRequest();
        request.requestId = requestId;
        request.userId = userId;
        request.sourceCartId = cartId;
        request.sourceCredentialHash = sourceHash;
        request.guestOrderHash = guestHash;
        request.guestExpiresAt = guestExpiresAt;
        request.payloadHash = payloadHash;
        request.order = order;
        return request;
    }
}
