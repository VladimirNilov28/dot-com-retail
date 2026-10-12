package ee.bytecore.backend.repositories.product;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ee.bytecore.backend.entities.product.Brand;

public interface BrandRepository extends JpaRepository<Brand, Long> {
    @Query("SELECT b FROM Brand b WHERE lower(b.name) = lower(:name)")
    Optional<Brand> findByNameIgnoreCase(@Param("name") String name);

    List<Brand> findAllByOrderByNameAsc();

    /** Rows of {productId, Brand} for products that have a brand. */
    @Query("SELECT p.id, b FROM Product p JOIN p.brand b WHERE p.id IN :productIds")
    List<Object[]> findAllByProductIdIn(@Param("productIds") Collection<Long> productIds);
}
