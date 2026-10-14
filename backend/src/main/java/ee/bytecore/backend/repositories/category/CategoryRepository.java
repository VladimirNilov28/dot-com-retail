package ee.bytecore.backend.repositories.category;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import ee.bytecore.backend.entities.category.Category;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    // Serialize hierarchy writers, including inserts/deletes, without blocking reads.
    @Modifying
    @Query(value = "LOCK TABLE categories IN SHARE ROW EXCLUSIVE MODE", nativeQuery = true)
    void lockHierarchy();

    Optional<Category> findBySlug(String slug);

    boolean existsByParentId(Long parentId);

    List<Category> findAllBySlugIn(java.util.Collection<String> slugs);

    @Query(value = "SELECT count(*) FROM product_categories WHERE category_id = :categoryId", nativeQuery = true)
    long countProductLinks(@org.springframework.data.repository.query.Param("categoryId") Long categoryId);
}
