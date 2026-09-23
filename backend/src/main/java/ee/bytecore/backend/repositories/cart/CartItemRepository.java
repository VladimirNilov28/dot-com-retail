package ee.bytecore.backend.repositories.cart;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.cart.CartItem;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {}
