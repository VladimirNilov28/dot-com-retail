package ee.bytecore.backend.entities.cart;

import ee.bytecore.backend.entities.product.ProductVariant;

import jakarta.persistence.*;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "cart_items", uniqueConstraints = @UniqueConstraint(columnNames = {"cart_id", "product_variant_id"}))
public class CartItem {
    protected CartItem() {}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_variant_id", nullable = false)
    private ProductVariant productVariant;

    @Positive @Column(name = "quantity", nullable = false)
    private Integer quantity;

    public static CartItem create(Cart cart, ProductVariant productVariant, Integer quantity) {
        CartItem cartItem = new CartItem();
        cartItem.cart = cart;
        cartItem.productVariant = productVariant;
        cartItem.quantity = quantity;
        return cartItem;
    }
}
