package ee.bytecore.backend.services;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.entities.product.Brand;
import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.repositories.product.BrandRepository;
import ee.bytecore.backend.repositories.product.ProductRepository;

import jakarta.persistence.EntityNotFoundException;

/**
 * Brands are first-class so they can be faceted reliably. Names are unique ignoring
 * case; a brand that still has products cannot be removed (FK RESTRICT).
 */
@Service
public class BrandService {

    public static final int MAX_NAME_LENGTH = 100;

    private final BrandRepository brands;
    private final ProductRepository products;

    public BrandService(BrandRepository brands, ProductRepository products) {
        this.brands = brands;
        this.products = products;
    }

    public List<Brand> findAll() {
        return brands.findAllByOrderByNameAsc();
    }

    public Brand require(Long id) {
        return brands.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Brand with id " + id + " not found"));
    }

    @Transactional
    public Brand getOrCreate(String name) {
        String normalized = name == null ? "" : name.strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException("Brand name must not be blank");
        if (normalized.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Brand name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        return brands.findByNameIgnoreCase(normalized).orElseGet(() -> brands.save(Brand.create(normalized)));
    }

    @Transactional
    public void assign(Long productId, Long brandId) {
        Product product = products.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("Product with id " + productId + " not found"));
        product.setBrand(brandId == null ? null : require(brandId));
        products.save(product);
    }
}
