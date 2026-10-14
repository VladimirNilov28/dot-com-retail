package ee.bytecore.backend.repositories.payment;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.payment.OrderRefund;

public interface OrderRefundRepository extends JpaRepository<OrderRefund, UUID> {
    Optional<OrderRefund> findByOrderId(Long orderId);
}
