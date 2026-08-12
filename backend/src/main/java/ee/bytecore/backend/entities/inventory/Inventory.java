package ee.bytecore.backend.entities.inventory;

import ee.bytecore.backend.entities.product.ProductVariant;
import jakarta.persistence.*;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(
    name = "inventory",
    uniqueConstraints = @UniqueConstraint(columnNames = {"product_variant_id", "warehouse_id"}))
public class Inventory {
  protected Inventory() {}

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "product_variant_id", nullable = false)
  private ProductVariant productVariant;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "warehouse_id", nullable = false)
  private Warehouse warehouse;

  @PositiveOrZero
  @Column(name = "quantity", nullable = false)
  private Integer quantity = 0;

  @UpdateTimestamp
  @Column(nullable = false)
  private Instant updatedAt;
}
