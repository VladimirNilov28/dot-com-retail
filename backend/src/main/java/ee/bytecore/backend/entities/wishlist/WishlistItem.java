package ee.bytecore.backend.entities.wishlist;

import java.time.Instant;

import ee.bytecore.backend.entities.product.ProductVariant;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

@Getter
@Setter
@Entity
@Table(
    name = "wishlist_items",
    uniqueConstraints = @UniqueConstraint(columnNames = {"wishlist_id", "product_variant_id"}))
public class WishlistItem {
  protected WishlistItem() {}

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "wishlist_id", nullable = false)
  private Wishlist wishlist;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "product_variant_id", nullable = false)
  private ProductVariant productVariant;

  @CreationTimestamp
  @Column(name = "added_at", nullable = false, updatable = false)
  private Instant addedAt;

  public static WishlistItem create(Wishlist wishlist, ProductVariant productVariant) {
    WishlistItem wishlistItem = new WishlistItem();
    wishlistItem.wishlist = wishlist;
    wishlistItem.productVariant = productVariant;
    return wishlistItem;
  }
}
