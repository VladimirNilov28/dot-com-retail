package ee.bytecore.backend.repositories.payment;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.payment.CheckoutRequest;

public interface CheckoutRequestRepository extends JpaRepository<CheckoutRequest, UUID> {
    Optional<CheckoutRequest> findByOrderPublicId(UUID publicId);
}
