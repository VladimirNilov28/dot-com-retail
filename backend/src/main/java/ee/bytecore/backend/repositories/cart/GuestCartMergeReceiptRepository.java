package ee.bytecore.backend.repositories.cart;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.cart.GuestCartMergeReceipt;

public interface GuestCartMergeReceiptRepository extends JpaRepository<GuestCartMergeReceipt, Long> {
    Optional<GuestCartMergeReceipt> findByUserIdAndRequestId(Long userId, UUID requestId);
}
