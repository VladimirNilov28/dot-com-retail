package ee.bytecore.backend.entities.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.enums.OrderStatus;
import ee.bytecore.backend.services.CheckoutValues;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "orders")
public class Order {
    protected Order() {}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @UuidGenerator(style = UuidGenerator.Style.RANDOM)
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Setter(lombok.AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "checkout_snapshot", updatable = false)
    private CheckoutValues.Snapshot checkoutSnapshot;

    public void initializeCheckout(CheckoutValues.Snapshot snapshot) {
        if (checkoutSnapshot != null) throw new IllegalStateException("Checkout snapshot already exists");
        checkoutSnapshot = snapshot;
    }

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @Column(name = "payment_status", nullable = false)
    private String paymentStatus = "UNKNOWN";

    @Column(name = "payment_id", unique = true)
    private UUID paymentId;

    @Column(name = "provider_transaction_id")
    private String providerTransactionId;

    @Column(name = "inventory_released_at")
    private Instant inventoryReleasedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_source")
    private String cancellationSource;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public static Order create(User user, OrderStatus status, BigDecimal totalAmount) {
        Order order = new Order();
        order.user = user;
        order.status = status;
        order.totalAmount = totalAmount;
        return order;
    }
}
