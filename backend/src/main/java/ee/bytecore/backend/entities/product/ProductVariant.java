package ee.bytecore.backend.entities.product;

import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Getter
@Setter
@Entity
@Table(name = "product_variants")
public class ProductVariant {
  protected ProductVariant() {}

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "product_id", nullable = false)
  private Product product;

  @Size(max = 255)
  @Column(name = "sku", nullable = false, unique = true)
  private String sku;

  @Column(name = "price", nullable = false, precision = 10, scale = 2)
  private BigDecimal price;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "attributes", nullable = false)
  private Map<String, Object> attributes;

  @Size(max = 64)
  @Column(name = "barcode", length = 64)
  private String barcode;

  @Column(name = "weight_grams")
  private Integer weightGrams;

  @Column(name = "is_active", nullable = false)
  private Boolean isActive;

  @CreationTimestamp
  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(nullable = false)
  private Instant updatedAt;
}
