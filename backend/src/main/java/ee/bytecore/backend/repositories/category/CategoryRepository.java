package ee.bytecore.backend.repositories.category;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.category.Category;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    Optional<Category> findBySlug(String slug);
}
