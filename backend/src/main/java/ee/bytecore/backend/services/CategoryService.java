package ee.bytecore.backend.services;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.entities.category.Category;
import ee.bytecore.backend.repositories.category.CategoryRepository;

import jakarta.validation.ConstraintViolationException;

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
        validateName(name);
        Category parent = resolveParent(parentId);
        return save(Category.create(name, slug, parent));
    }

    @Transactional
    public Category update(Long id, String name, String slug, Long parentId) {
        Category existing = categoryRepository
                .findById(id)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        String.format("Category with id %s not found", id)));

        if (name != null) {
            validateName(name);
            existing.setName(name);
        }
        if (slug != null) existing.setSlug(slug);
        if (parentId != null) existing.setParent(resolveParent(parentId));

        return save(existing);
    }

    public boolean deleteById(Long id) {
        if (categoryRepository.findById(id).isEmpty()) {
            return false;
        }
        categoryRepository.deleteById(id);
        return true;
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Category name must not be blank");
        }
    }

    private Category resolveParent(Long parentId) {
        if (parentId == null) {
            return null;
        }
        return categoryRepository
                .findById(parentId)
                .orElseThrow(() ->
                        new IllegalArgumentException(String.format("Parent category with id %s not found", parentId)));
    }

    private Category save(Category category) {
        try {
            return categoryRepository.save(category);
        } catch (ConstraintViolationException e) {
            String violations = e.getConstraintViolations().stream()
                    .map(v -> String.format("%s: %s", v.getPropertyPath(), v.getMessage()))
                    .collect(Collectors.joining("; "));
            throw new IllegalArgumentException(String.format("Invalid category: %s", violations));
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException(
                    String.format("Category with slug '%s' already exists", category.getSlug()));
        }
    }
}
