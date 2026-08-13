package ee.bytecore.backend.repositories.category;

import ee.bytecore.backend.entities.category.Category;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {
}
