package ee.bytecore.backend.services;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.entities.product.ProductImage;
import ee.bytecore.backend.repositories.product.ProductImageRepository;
import ee.bytecore.backend.repositories.product.ProductRepository;
import ee.bytecore.backend.services.media.ImageInspector;
import ee.bytecore.backend.services.media.ImageResizer;
import ee.bytecore.backend.services.media.ImageSize;
import ee.bytecore.backend.services.media.InvalidImageException;
import ee.bytecore.backend.services.media.MediaStorage;

import jakarta.persistence.EntityNotFoundException;

/**
 * Images attach to a Product (not a variant), ordered by {@code position}; position 0 is
 * the primary image. Bytes live in {@link MediaStorage}; rows only reference them. Every upload
 * is kept as the original plus re-encoded {@link ImageSize} variants; variants missing for older
 * uploads are generated lazily on first delivery.
 */
@Service
public class ProductImageService {

    public static final int MAX_IMAGES_PER_PRODUCT = 12;
    public static final int MAX_ALT_LENGTH = 200;

    private final ProductImageRepository images;
    private final ProductRepository products;
    private final MediaStorage storage;

    /** Variant files are generated at most once per key+size at a time (striped by hash). */
    private final Object[] variantLocks = new Object[64];

    /** Decoding is memory heavy; bound concurrent lazy generation across all keys. */
    private final Semaphore rendering = new Semaphore(2);

    public record Delivered(byte[] bytes, String contentType) {}

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ee.bytecore.backend.services.audit.AuditService audit;

    public ProductImageService(ProductImageRepository images, ProductRepository products, MediaStorage storage) {
        this.images = images;
        this.products = products;
        this.storage = storage;
        for (int i = 0; i < variantLocks.length; i++) variantLocks[i] = new Object();
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
        java.util.Map<ImageSize, byte[]> variants = new java.util.EnumMap<>(ImageSize.class);
        for (ImageSize size : ImageSize.values()) {
            variants.put(size, render(bytes, inspected.contentType(), size));
        }
        String key = UUID.randomUUID() + "." + inspected.extension();
        try {
            storage.store(key, bytes);
            variants.forEach((size, variant) -> storage.storeVariant(key, size, variant));
            ProductImage saved = images.saveAndFlush(ProductImage.create(
                    productId,
                    key,
                    inspected.contentType(),
                    bytes.length,
                    inspected.width(),
                    inspected.height(),
                    alt,
                    (int) count));
            if (audit != null)
                audit.record(
                        "uploadProductImage",
                        "PRODUCT_IMAGE",
                        saved.getId(),
                        java.util.Map.of(
                                "productId",
                                productId,
                                "contentType",
                                inspected.contentType(),
                                "byteSize",
                                bytes.length));
            return saved;
        } catch (RuntimeException e) {
            storage.delete(key);
            throw e;
        }
    }

    /**
     * Bytes for a registered key at the requested size. A missing variant (uploads that predate
     * variants) is generated from the original, stored atomically and reused afterwards. If the
     * original cannot be decoded, the original bytes are served unchanged rather than failing.
     */
    public Optional<Delivered> deliver(String key, ImageSize size) {
        if (key == null || size == null || !MediaStorage.KEY.matcher(key).matches()) return Optional.empty();
        Optional<ProductImage> image = images.findByStorageKey(key);
        if (image.isEmpty()) return Optional.empty();
        String contentType = image.get().getContentType();
        Optional<byte[]> variant = storage.readVariant(key, size);
        if (variant.isPresent()) return Optional.of(new Delivered(variant.get(), contentType));
        synchronized (variantLocks[Math.floorMod((key + size).hashCode(), variantLocks.length)]) {
            variant = storage.readVariant(key, size);
            if (variant.isPresent()) return Optional.of(new Delivered(variant.get(), contentType));
            Optional<byte[]> original = storage.read(key);
            if (original.isEmpty()) return Optional.empty();
            byte[] rendered;
            try {
                rendered = render(original.get(), contentType, size);
            } catch (InvalidImageException e) {
                return Optional.of(new Delivered(original.get(), contentType));
            }
            storage.storeVariant(key, size, rendered);
            return Optional.of(new Delivered(rendered, contentType));
        }
    }

    private byte[] render(byte[] bytes, String contentType, ImageSize size) {
        try {
            rendering.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InvalidImageException(503, "The image could not be processed right now.");
        }
        try {
            return ImageResizer.render(bytes, contentType, size).bytes();
        } finally {
            rendering.release();
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
