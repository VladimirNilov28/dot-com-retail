package ee.bytecore.backend.services;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.repositories.product.ProductRepository;
import ee.bytecore.backend.repositories.product.ProductVariantRepository;

import jakarta.persistence.EntityNotFoundException;

@Service
public class ProductVariantService {

    private final ProductVariantRepository productVariantRepository;
    private final ProductRepository productRepository;

    public ProductVariantService(
            ProductVariantRepository productVariantRepository, ProductRepository productRepository) {
        this.productVariantRepository = productVariantRepository;
        this.productRepository = productRepository;
    }

    public Optional<ProductVariant> findById(Long id) {
        return productVariantRepository.findById(id);
    }

    public List<ProductVariant> findAllByProductId(Long productId) {
        return productVariantRepository.findAllByProductId(productId);
    }

    public ProductVariant create(
            Long productId,
            String sku,
            BigDecimal price,
            Map<String, Object> attributes,
            String barcode,
            Integer weightGrams) {
        Product product = productRepository
                .findById(productId)
                .orElseThrow(
                        () -> new EntityNotFoundException(String.format("Product with id %s not found", productId)));

        ProductVariant variant = ProductVariant.create(product, sku, price);
        if (attributes != null) variant.setAttributes(attributes);
        if (barcode != null) variant.setBarcode(barcode);
        if (weightGrams != null) variant.setWeightGrams(weightGrams);

        return productVariantRepository.save(variant);
    }

    @Transactional
    public ProductVariant update(
            Long id,
            String sku,
            BigDecimal price,
            Map<String, Object> attributes,
            String barcode,
            Integer weightGrams,
            Boolean isActive) {
        ProductVariant existing = productVariantRepository
                .findById(id)
                .orElseThrow(
                        () -> new EntityNotFoundException(String.format("ProductVariant with id %s not found", id)));

        if (sku != null) existing.setSku(sku);
        if (price != null) existing.setPrice(price);
        if (attributes != null) existing.setAttributes(attributes);
        if (barcode != null) existing.setBarcode(barcode);
        if (weightGrams != null) existing.setWeightGrams(weightGrams);
        if (isActive != null) existing.setIsActive(isActive);

        return productVariantRepository.save(existing);
    }

    public boolean deleteById(Long id) {
        if (productVariantRepository.findById(id).isEmpty()) {
            return false;
        }
        productVariantRepository.deleteById(id);
        return true;
    }
}
