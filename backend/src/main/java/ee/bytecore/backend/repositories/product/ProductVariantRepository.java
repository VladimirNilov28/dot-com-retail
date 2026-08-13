package ee.bytecore.backend.repositories.product;

import ee.bytecore.backend.entities.product.ProductVariant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {
}
