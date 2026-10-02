package ee.bytecore.backend.repositories.payment;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.payment.OrderItem;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    List<OrderItem> findAllByOrderId(Long orderId);

    List<OrderItem> findAllByOrderIdIn(List<Long> orderIds);
}
