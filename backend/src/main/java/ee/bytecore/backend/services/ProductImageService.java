package ee.bytecore.backend.services;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.entities.product.ProductImage;
import ee.bytecore.backend.repositories.product.ProductImageRepository;
import ee.bytecore.backend.repositories.product.ProductRepository;
import ee.bytecore.backend.services.media.ImageInspector;
import ee.bytecore.backend.services.media.MediaStorage;

import jakarta.persistence.EntityNotFoundException;

/**
 * Images attach to a Product (not a variant), ordered by {@code position}; position 0 is
 * the primary image. Bytes live in {@link MediaStorage}; rows only reference them.
 */
@Service
public class ProductImageService {

    public static final int MAX_IMAGES_PER_PRODUCT = 12;
    public static final int MAX_ALT_LENGTH = 200;

    private final ProductImageRepository images;
    private final ProductRepository products;
    private final MediaStorage storage;

    public ProductImageService(ProductImageRepository images, ProductRepository products, MediaStorage storage) {
        this.images = images;
        this.products = products;
        this.storage = storage;
    }

    public List<ProductImage> list(Long productId) {
        return images.findAllByProductIdOrderByPositionAscIdAsc(productId);
    }

    public Optional<ProductImage> findByKey(String key) {
        return images.findByStorageKey(key);
    }

    @Transactional
    public ProductImage upload(Long productId, byte[] bytes, String altText) {
        Product product = products.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("Product with id " + productId + " not found"));
        ImageInspector.Inspected inspected = ImageInspector.inspect(bytes);
        long count = images.countByProductId(productId);
        if (count >= MAX_IMAGES_PER_PRODUCT) {
            throw new IllegalArgumentException("A product can have at most " + MAX_IMAGES_PER_PRODUCT + " images");
        }
        String alt = altText == null || altText.isBlank() ? product.getName() : normalizeAlt(altText);
        String key = UUID.randomUUID() + "." + inspected.extension();
        storage.store(key, bytes);
        try {
            return images.saveAndFlush(ProductImage.create(
                    productId,
                    key,
                    inspected.contentType(),
                    bytes.length,
                    inspected.width(),
                    inspected.height(),
                    alt,
                    (int) count));
        } catch (RuntimeException e) {
            storage.delete(key);
            throw e;
        }
    }

    @Transactional
    public ProductImage updateAlt(Long imageId, String altText) {
        ProductImage image = require(imageId);
        image.setAltText(normalizeAlt(altText));
        return images.save(image);
    }

    @Transactional
    public List<ProductImage> setPrimary(Long imageId) {
        ProductImage image = require(imageId);
        List<ProductImage> ordered = new ArrayList<>(list(image.getProductId()));
        ordered.removeIf(i -> i.getId().equals(imageId));
        ordered.add(0, image);
        return renumber(ordered);
    }

    @Transactional
    public List<ProductImage> reorder(Long productId, List<Long> imageIds) {
        List<ProductImage> current = list(productId);
        if (imageIds == null
                || imageIds.size() != current.size()
                || !new HashSet<>(imageIds)
                        .equals(new HashSet<>(
                                current.stream().map(ProductImage::getId).toList()))) {
            throw new IllegalArgumentException("The image order must list each of the product's images exactly once");
        }
        List<ProductImage> ordered = imageIds.stream()
                .map(id -> current.stream()
                        .filter(i -> i.getId().equals(id))
                        .findFirst()
                        .orElseThrow())
                .toList();
        return renumber(ordered);
    }

    @Transactional
    public boolean delete(Long imageId) {
        Optional<ProductImage> found = images.findById(imageId);
        if (found.isEmpty()) return false;
        ProductImage image = found.get();
        images.delete(image);
        images.flush();
        List<ProductImage> remaining = new ArrayList<>(list(image.getProductId()));
        renumber(remaining);
        // The bytes are removed only when no other row still references the same key.
        if (images.countByStorageKey(image.getStorageKey()) == 0) storage.delete(image.getStorageKey());
        return true;
    }

    private List<ProductImage> renumber(List<ProductImage> ordered) {
        for (int i = 0; i < ordered.size(); i++) ordered.get(i).setPosition(i);
        return images.saveAll(ordered);
    }

    private ProductImage require(Long imageId) {
        return images.findById(imageId)
                .orElseThrow(() -> new EntityNotFoundException("Image with id " + imageId + " not found"));
    }

    private static String normalizeAlt(String altText) {
        String alt = altText == null ? "" : altText.strip();
        if (alt.isEmpty()) throw new IllegalArgumentException("Alternative text must not be blank");
        if (alt.length() > MAX_ALT_LENGTH) {
            throw new IllegalArgumentException("Alternative text must be at most " + MAX_ALT_LENGTH + " characters");
        }
        return alt;
    }
}
