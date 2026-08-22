package ee.bytecore.backend.repositories.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.payment.Order;

public interface OrderRepository extends JpaRepository<Order, Long> {}
