package ee.bytecore.backend.repositories.product;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ee.bytecore.backend.entities.product.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findBySlug(String slug);

    @Query("SELECT DISTINCT p FROM Product p LEFT JOIN FETCH p.categories WHERE p.id IN :productIds")
    List<Product> findAllByIdInWithCategories(@Param("productIds") List<Long> productIds);
}
