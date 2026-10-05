package ee.bytecore.backend.repositories.category;

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
}
