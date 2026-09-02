package ee.bytecore.backend.entities.user;

import java.time.Instant;

import ee.bytecore.backend.enums.PaymentMethodType;

import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "user_payment_methods")
public class  UserPaymentMethod {
    protected UserPaymentMethod() {}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Size(max = 255) @Column(name = "provider")
    private String provider;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "type", nullable = false)
    private PaymentMethodType type;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public static UserPaymentMethod create(User user, String provider, PaymentMethodType type) {
        UserPaymentMethod userPaymentMethod = new UserPaymentMethod();
        userPaymentMethod.user = user;
        userPaymentMethod.provider = provider;
        userPaymentMethod.type = type;
        return userPaymentMethod;
    }
}
