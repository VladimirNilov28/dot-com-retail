package ee.bytecore.backend.services;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.entities.category.Category;
import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.repositories.category.CategoryRepository;
import ee.bytecore.backend.repositories.product.ProductRepository;

import jakarta.persistence.EntityNotFoundException;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    public ProductService(ProductRepository productRepository, CategoryRepository categoryRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
    }

    public Optional<Product> findById(Long id) {
        return productRepository.findById(id);
    }

    public Optional<Product> findBySlug(String slug) {
        return productRepository.findBySlug(slug);
    }

    public List<Product> findAll() {
        return productRepository.findAll();
    }

    public Product create(String name, String slug, String description, List<Long> categoryIds) {
        Product product = Product.create(name, slug, description);
        product.setCategories(resolveCategories(categoryIds));
        return productRepository.save(product);
    }

    @Transactional
    public Product update(Long id, String name, String slug, String description, List<Long> categoryIds) {
        Product existing = productRepository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(String.format("Product with id %s not found", id)));

        if (name != null) existing.setName(name);
        if (slug != null) existing.setSlug(slug);
        if (description != null) existing.setDescription(description);
        if (categoryIds != null) existing.setCategories(resolveCategories(categoryIds));

        return productRepository.save(existing);
    }

    public boolean deleteById(Long id) {
        if (productRepository.findById(id).isEmpty()) {
            return false;
        }
        productRepository.deleteById(id);
        return true;
    }

    private Set<Category> resolveCategories(List<Long> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return new HashSet<>();
        }
        return new HashSet<>(categoryRepository.findAllById(categoryIds));
    }
}
