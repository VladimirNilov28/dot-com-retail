package ee.bytecore.backend.repositories.payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.payment.RefundRequest;

public interface RefundRequestRepository extends JpaRepository<RefundRequest, UUID> {
    List<RefundRequest> findAllByOrderIdOrderByCreatedAtAsc(Long orderId);

    Optional<RefundRequest> findFirstByOrderIdAndStatus(Long orderId, String status);
}
