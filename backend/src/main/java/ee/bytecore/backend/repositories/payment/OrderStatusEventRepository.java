package ee.bytecore.backend.repositories.payment;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.payment.OrderStatusEvent;

public interface OrderStatusEventRepository extends JpaRepository<OrderStatusEvent, Long> {
    List<OrderStatusEvent> findAllByOrderIdOrderByOccurredAtAscIdAsc(Long orderId);
}
