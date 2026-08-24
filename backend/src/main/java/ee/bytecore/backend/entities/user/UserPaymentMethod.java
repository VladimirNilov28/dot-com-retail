package ee.bytecore.backend.entities.user;

import java.time.Instant;

import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Getter
@Setter
@Entity
@Table(name = "user_payment_methods")
public class UserPaymentMethod {
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

    @Size(max = 255) @Column(name = "type")
    private String type;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public static UserPaymentMethod create(User user, String provider, String type) {
        UserPaymentMethod userPaymentMethod = new UserPaymentMethod();
        userPaymentMethod.user = user;
        userPaymentMethod.provider = provider;
        userPaymentMethod.type = type;
        return userPaymentMethod;
    }
}
