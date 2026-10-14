package ee.bytecore.backend.repositories.product;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.product.ProductImage;

public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {
    List<ProductImage> findAllByProductIdOrderByPositionAscIdAsc(Long productId);

    List<ProductImage> findAllByProductIdInOrderByPositionAscIdAsc(Collection<Long> productIds);

    long countByProductId(Long productId);

    long countByStorageKey(String storageKey);

    boolean existsByStorageKey(String storageKey);

    java.util.Optional<ProductImage> findByStorageKey(String storageKey);
}
