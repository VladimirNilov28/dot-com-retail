package ee.bytecore.backend.repositories.payment;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.payment.PaymentOutboxEvent;

public interface PaymentOutboxEventRepository extends JpaRepository<PaymentOutboxEvent, UUID> {
    List<PaymentOutboxEvent> findAllByPublishedFalseOrderByCreatedAtAsc();
}
