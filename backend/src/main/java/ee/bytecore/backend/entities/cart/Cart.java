package ee.bytecore.backend.entities.cart;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import ee.bytecore.backend.entities.user.User;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Getter
@Setter
@Entity
@Table(name = "carts")
public class Cart {
  protected Cart() {}

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @OneToMany(mappedBy = "cart", fetch = FetchType.LAZY)
  private List<CartItem> items = new ArrayList<>();

  @CreationTimestamp
  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(nullable = false)
  private Instant updatedAt;

  public static Cart create(User user) {
    Cart cart = new Cart();
    cart.user = user;
    return cart;
  }
}
