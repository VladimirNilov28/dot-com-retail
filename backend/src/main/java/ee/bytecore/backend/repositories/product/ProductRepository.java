package ee.bytecore.backend.repositories.product;

import ee.bytecore.backend.entities.product.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
