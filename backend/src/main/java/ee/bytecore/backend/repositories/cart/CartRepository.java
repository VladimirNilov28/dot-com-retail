package ee.bytecore.backend.repositories.cart;

import ee.bytecore.backend.entities.cart.Cart;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartRepository extends JpaRepository<Cart, Long> {
}
