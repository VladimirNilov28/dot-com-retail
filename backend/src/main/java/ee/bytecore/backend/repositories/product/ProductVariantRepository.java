package ee.bytecore.backend.repositories.product;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import ee.bytecore.backend.entities.product.ProductVariant;

import jakarta.persistence.LockModeType;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {
    List<ProductVariant> findAllByProductId(Long productId);

    List<ProductVariant> findAllByProductIdIn(List<Long> productIds);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select v from ProductVariant v where v.id = :id")
    Optional<ProductVariant> findByIdForCartValidation(Long id);
}
