package ee.bytecore.backend.repositories.product;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.product.ProductVariant;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {}
