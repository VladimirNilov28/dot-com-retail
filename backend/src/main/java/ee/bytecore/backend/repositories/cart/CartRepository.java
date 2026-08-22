package ee.bytecore.backend.repositories.cart;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.cart.Cart;

public interface CartRepository extends JpaRepository<Cart, Long> {}
