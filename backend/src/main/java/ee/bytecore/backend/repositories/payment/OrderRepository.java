package ee.bytecore.backend.repositories.payment;

import ee.bytecore.backend.entities.payment.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
}
