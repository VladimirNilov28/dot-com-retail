package ee.bytecore.backend.entities.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import ee.bytecore.backend.enums.PaymentStatus;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
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
@Table(name = "payment_details")
public class PaymentDetails {
    protected PaymentDetails() {}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @UuidGenerator(style = UuidGenerator.Style.RANDOM)
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @PositiveOrZero @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @NotNull @Size(max = 255) @Column(name = "provider", nullable = false)
    private String provider;

    @NotNull @Size(max = 255) @Column(name = "type", nullable = false)
    private String type;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    private PaymentStatus status = PaymentStatus.PENDING;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public static PaymentDetails create(Order order, BigDecimal amount, String provider, String type) {
        PaymentDetails paymentDetails = new PaymentDetails();
        paymentDetails.order = order;
        paymentDetails.amount = amount;
        paymentDetails.provider = provider;
        paymentDetails.type = type;
        return paymentDetails;
    }
}
