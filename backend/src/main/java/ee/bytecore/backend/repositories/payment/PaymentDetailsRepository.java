package ee.bytecore.backend.repositories.payment;

import ee.bytecore.backend.entities.payment.PaymentDetails;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentDetailsRepository extends JpaRepository<PaymentDetails, Long> {
}
