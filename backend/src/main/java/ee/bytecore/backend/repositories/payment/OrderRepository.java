package ee.bytecore.backend.repositories.payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.payment.Order;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findAllByUserId(Long userId);

    Optional<Order> findByPublicId(UUID publicId);
}
