package ee.bytecore.backend.repositories.payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import ee.bytecore.backend.entities.payment.Order;

import jakarta.persistence.LockModeType;

public interface OrderRepository extends JpaRepository<Order, Long> {
    @Override
    @EntityGraph(attributePaths = "user")
    Optional<Order> findById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(Long id);

    @EntityGraph(attributePaths = "user")
    List<Order> findAllByUserId(Long userId);

    @EntityGraph(attributePaths = "user")
    Optional<Order> findByPublicId(UUID publicId);
}
