package ee.bytecore.backend.services;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.repositories.product.ProductRepository;
import ee.bytecore.backend.repositories.product.ProductVariantRepository;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;

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
        return create(productId, sku, price, attributes, barcode, weightGrams, null, null, null);
    }

    public ProductVariant create(
            Long productId,
            String sku,
            BigDecimal price,
            Map<String, Object> attributes,
            String barcode,
            Integer weightGrams,
            Integer lengthMm,
            Integer widthMm,
            Integer heightMm) {
        validatePrice(price);
        validateWeight(weightGrams);
        validateDimensions(lengthMm, widthMm, heightMm);

        Product product = productRepository
                .findById(productId)
                .orElseThrow(
                        () -> new EntityNotFoundException(String.format("Product with id %s not found", productId)));

        ProductVariant variant = ProductVariant.create(product, sku, price);
        if (attributes != null) variant.setAttributes(attributes);
        if (barcode != null) variant.setBarcode(barcode);
        if (weightGrams != null) variant.setWeightGrams(weightGrams);
        variant.setLengthMm(lengthMm);
        variant.setWidthMm(widthMm);
        variant.setHeightMm(heightMm);

        return save(variant);
    }

    public ProductVariant update(
            Long id,
            String sku,
            BigDecimal price,
            Map<String, Object> attributes,
            String barcode,
            Integer weightGrams,
            Boolean isActive) {
        return update(id, sku, price, attributes, barcode, weightGrams, isActive, null, null, null, false);
    }

    @Transactional
    public ProductVariant update(
            Long id,
            String sku,
            BigDecimal price,
            Map<String, Object> attributes,
            String barcode,
            Integer weightGrams,
            Boolean isActive,
            Integer lengthMm,
            Integer widthMm,
            Integer heightMm,
            boolean clearDimensions) {
        ProductVariant existing = productVariantRepository
                .findById(id)
                .orElseThrow(
                        () -> new EntityNotFoundException(String.format("ProductVariant with id %s not found", id)));

        if (sku != null) existing.setSku(sku);
        if (price != null) {
            validatePrice(price);
            existing.setPrice(price);
        }
        if (attributes != null) existing.setAttributes(attributes);
        if (barcode != null) existing.setBarcode(barcode);
        if (weightGrams != null) {
            validateWeight(weightGrams);
            existing.setWeightGrams(weightGrams);
        }
        if (isActive != null) existing.setIsActive(isActive);
        if (clearDimensions) {
            if (lengthMm != null || widthMm != null || heightMm != null) {
                throw new IllegalArgumentException("Dimensions cannot be both set and cleared");
            }
            existing.setLengthMm(null);
            existing.setWidthMm(null);
            existing.setHeightMm(null);
        } else if (lengthMm != null || widthMm != null || heightMm != null) {
            validateDimensions(lengthMm, widthMm, heightMm);
            existing.setLengthMm(lengthMm);
            existing.setWidthMm(widthMm);
            existing.setHeightMm(heightMm);
        }

        return save(existing);
    }

    public static final int MAX_WEIGHT_GRAMS = 1_000_000;
    public static final int MAX_DIMENSION_MM = 100_000;

    private void validateWeight(Integer weightGrams) {
        if (weightGrams != null && (weightGrams <= 0 || weightGrams > MAX_WEIGHT_GRAMS)) {
            throw new IllegalArgumentException("Weight must be between 1 and " + MAX_WEIGHT_GRAMS + " grams");
        }
    }

    /** All three dimensions or none; each a positive integer number of millimetres. */
    private void validateDimensions(Integer lengthMm, Integer widthMm, Integer heightMm) {
        int present = (lengthMm == null ? 0 : 1) + (widthMm == null ? 0 : 1) + (heightMm == null ? 0 : 1);
        if (present == 0) return;
        if (present != 3) throw new IllegalArgumentException("Length, width and height must be specified together");
        for (Integer value : new Integer[] {lengthMm, widthMm, heightMm}) {
            if (value <= 0 || value > MAX_DIMENSION_MM) {
                throw new IllegalArgumentException("Each dimension must be between 1 and " + MAX_DIMENSION_MM + " mm");
            }
        }
    }

    @Transactional
    public boolean deleteById(Long id) {
        if (productVariantRepository.findById(id).isEmpty()) {
            return false;
        }
        try {
            productVariantRepository.deleteById(id);
            productVariantRepository.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new IllegalArgumentException(
                    "Variant has order or cart history and cannot be deleted; deactivate it instead");
        }
        return true;
    }

    private void validatePrice(BigDecimal price) {
        if (price != null && price.signum() < 0) {
            throw new IllegalArgumentException("Price must not be negative");
        }
    }

    private ProductVariant save(ProductVariant variant) {
        try {
            return productVariantRepository.save(variant);
        } catch (ConstraintViolationException e) {
            String violations = e.getConstraintViolations().stream()
                    .map(v -> String.format("%s: %s", v.getPropertyPath(), v.getMessage()))
                    .collect(Collectors.joining("; "));
            throw new IllegalArgumentException(String.format("Invalid product variant: %s", violations));
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException(
                    String.format("Product variant with SKU '%s' already exists", variant.getSku()));
        }
    }
}
