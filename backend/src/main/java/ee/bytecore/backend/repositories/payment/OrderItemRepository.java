package ee.bytecore.backend.repositories.payment;

import ee.bytecore.backend.entities.payment.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
}
