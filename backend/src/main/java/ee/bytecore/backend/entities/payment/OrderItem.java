package ee.bytecore.backend.entities.payment;

import ee.bytecore.backend.entities.product.ProductVariant;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

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

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "order_id", nullable = false)
  private Order order;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "product_variant_id", nullable = false)
  private ProductVariant productVariant;

  @Positive
  @Column(name = "quantity", nullable = false)
  private Integer quantity;

  @PositiveOrZero
  @Column(name = "price_at_purchase", nullable = false, precision = 10, scale = 2)
  private BigDecimal priceAtPurchase;
}
