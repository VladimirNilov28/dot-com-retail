package ee.bytecore.backend.repositories.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.payment.OrderItem;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {}
