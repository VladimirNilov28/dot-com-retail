package ee.bytecore.backend.repositories.cart;

import ee.bytecore.backend.entities.cart.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
}
