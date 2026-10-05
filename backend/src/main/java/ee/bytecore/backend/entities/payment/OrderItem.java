package ee.bytecore.backend.entities.payment;

import java.math.BigDecimal;
import java.util.UUID;

import ee.bytecore.backend.entities.inventory.Inventory;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.services.CheckoutValues;

import jakarta.persistence.*;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "order_items")
public class OrderItem {
    protected OrderItem() {}

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_variant_id", nullable = false)
    private ProductVariant productVariant;

    /**
     * The exact Inventory row stock was decremented from for this line item,
     * so early cancellation can restore stock to its precise source instead
     * of guessing a warehouse.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inventory_id", nullable = false)
    private Inventory inventory;

    @Positive @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @PositiveOrZero @Column(name = "price_at_purchase", nullable = false, precision = 10, scale = 2)
    private BigDecimal priceAtPurchase;

    @Setter(lombok.AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "checkout_snapshot", updatable = false)
    private CheckoutValues.Line checkoutSnapshot;

    public void initializeCheckout(CheckoutValues.Line snapshot) {
        if (checkoutSnapshot != null) throw new IllegalStateException("Checkout snapshot already exists");
        checkoutSnapshot = snapshot;
    }

    public static OrderItem create(
            Order order,
            ProductVariant productVariant,
            Inventory inventory,
            Integer quantity,
            BigDecimal priceAtPurchase) {
        OrderItem orderItem = new OrderItem();
        orderItem.order = order;
        orderItem.productVariant = productVariant;
        orderItem.inventory = inventory;
        orderItem.quantity = quantity;
        orderItem.priceAtPurchase = priceAtPurchase;
        return orderItem;
    }
}
