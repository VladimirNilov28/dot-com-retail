package ee.bytecore.backend.repositories.product;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.product.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findBySlug(String slug);
}
