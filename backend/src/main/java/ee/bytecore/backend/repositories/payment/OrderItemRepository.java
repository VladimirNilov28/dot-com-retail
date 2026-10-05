package ee.bytecore.backend.repositories.payment;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.payment.OrderItem;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    @Override
    @EntityGraph(attributePaths = "order.user")
    Optional<OrderItem> findById(Long id);

    List<OrderItem> findAllByOrderId(Long orderId);

    List<OrderItem> findAllByOrderIdIn(List<Long> orderIds);
}
