package ee.bytecore.backend.repositories.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.payment.PaymentDetails;

public interface PaymentDetailsRepository extends JpaRepository<PaymentDetails, Long> {}
