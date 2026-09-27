package ee.bytecore.backend.services;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.entities.category.Category;
import ee.bytecore.backend.repositories.category.CategoryRepository;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    public Optional<Category> findById(Long id) {
        return categoryRepository.findById(id);
    }

    public Optional<Category> findBySlug(String slug) {
        return categoryRepository.findBySlug(slug);
    }

    public List<Category> findAll() {
        return categoryRepository.findAll();
    }

    public Category create(String name, String slug, Long parentId) {
        Category parent = resolveParent(parentId);
        return categoryRepository.save(Category.create(name, slug, parent));
    }

    @Transactional
    public Category update(Long id, String name, String slug, Long parentId) {
        Category existing = categoryRepository
                .findById(id)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        String.format("Category with id %s not found", id)));

        if (name != null) existing.setName(name);
        if (slug != null) existing.setSlug(slug);
        if (parentId != null) existing.setParent(resolveParent(parentId));

        return categoryRepository.save(existing);
    }

    public boolean deleteById(Long id) {
        if (categoryRepository.findById(id).isEmpty()) {
            return false;
        }
        categoryRepository.deleteById(id);
        return true;
    }

    private Category resolveParent(Long parentId) {
        return parentId == null ? null : categoryRepository.findById(parentId).orElse(null);
    }
}
