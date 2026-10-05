package ee.bytecore.backend.entities.cart;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ee.bytecore.backend.entities.user.User;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "guest_cart_merge_receipts")
@Getter
public class GuestCartMergeReceipt {
    protected GuestCartMergeReceipt() {}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "source_cart_id", nullable = false)
    private Long sourceCartId;

    @Column(name = "source_credential_hash", nullable = false, length = 64)
    private String sourceCredentialHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "merged_items", nullable = false)
    private List<CartMergeLine> mergedItems;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public static GuestCartMergeReceipt create(
            User user, UUID requestId, Cart source, List<CartMergeLine> items, Instant now, Instant expiresAt) {
        GuestCartMergeReceipt receipt = new GuestCartMergeReceipt();
        receipt.user = user;
        receipt.requestId = requestId;
        receipt.sourceCartId = source.getId();
        receipt.sourceCredentialHash = source.getGuestCredentialHash();
        receipt.mergedItems = List.copyOf(items);
        receipt.createdAt = now;
        receipt.expiresAt = expiresAt;
        return receipt;
    }
}
