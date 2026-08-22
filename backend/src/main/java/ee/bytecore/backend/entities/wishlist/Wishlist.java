package ee.bytecore.backend.entities.wishlist;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import ee.bytecore.backend.entities.user.User;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

@Getter
@Setter
@Entity
@Table(name = "wishlists")
public class Wishlist {
  protected Wishlist() {}

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false, unique = true)
  private User user;

  @OneToMany(mappedBy = "wishlist", fetch = FetchType.LAZY)
  private List<WishlistItem> items = new ArrayList<>();

  @CreationTimestamp
  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  public static Wishlist create(User user) {
    Wishlist wishlist = new Wishlist();
    wishlist.user = user;
    return wishlist;
  }
}
