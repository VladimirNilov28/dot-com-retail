package ee.bytecore.backend.entities.shipping;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** A staff-managed checkout delivery option (table {@code shipping_options}). */
@Getter
@Setter
@Entity
@Table(name = "shipping_options")
public class DeliveryOption {
    protected DeliveryOption() {}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @Setter(lombok.AccessLevel.NONE)
    private Long id;

    @Column(name = "code", nullable = false, length = 20, updatable = false)
    @Setter(lombok.AccessLevel.NONE)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "estimated_min_days", nullable = false)
    private Integer estimatedMinDays;

    @Column(name = "estimated_max_days", nullable = false)
    private Integer estimatedMaxDays;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    @Setter(lombok.AccessLevel.NONE)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    @Setter(lombok.AccessLevel.NONE)
    private Instant updatedAt;

    public static DeliveryOption create(String code) {
        DeliveryOption option = new DeliveryOption();
        option.code = code;
        option.currency = "EUR";
        option.active = true;
        option.sortOrder = 0;
        return option;
    }
}
