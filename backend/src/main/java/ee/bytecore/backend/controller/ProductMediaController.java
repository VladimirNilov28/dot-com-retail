package ee.bytecore.backend.controller;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import ee.bytecore.backend.entities.product.ProductImage;
import ee.bytecore.backend.services.ProductImageService;
import ee.bytecore.backend.services.media.InvalidImageException;
import ee.bytecore.backend.services.media.MediaStorage;

import jakarta.persistence.EntityNotFoundException;

/**
 * Binary side of product media. Management (list/alt/order/delete) is GraphQL; only upload and
 * byte delivery are HTTP. Uploads are staff-only; delivery is public but restricted to keys that
 * are registered in {@code product_images}.
 */
@RestController
public class ProductMediaController {

    private static final Pattern PRODUCT_ID = Pattern.compile("[0-9]{1,18}");

    private final ProductImageService images;
    private final MediaStorage storage;

    public ProductMediaController(ProductImageService images, MediaStorage storage) {
        this.images = images;
        this.storage = storage;
    }

    @PostMapping("/admin/media/products/{productId}/images")
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public ResponseEntity<Map<String, Object>> upload(
            @PathVariable String productId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "altText", required = false) String altText)
            throws IOException {
        if (!PRODUCT_ID.matcher(productId).matches()) {
            throw new EntityNotFoundException("Product not found");
        }
        ProductImage saved = images.upload(Long.valueOf(productId), file.getBytes(), altText);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Cache-Control", "no-store")
                .body(Map.of(
                        "id", saved.getId().toString(),
                        "url", saved.url(),
                        "altText", saved.getAltText(),
                        "width", saved.getWidth(),
                        "height", saved.getHeight(),
                        "position", saved.getPosition()));
    }

    @GetMapping("/media/products/{key}")
    public ResponseEntity<byte[]> serve(@PathVariable String key) {
        if (!MediaStorage.KEY.matcher(key).matches())
            return ResponseEntity.notFound().build();
        var image = images.findByKey(key);
        if (image.isEmpty()) return ResponseEntity.notFound().build();
        var bytes = storage.read(key);
        if (bytes.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.get().getContentType()))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365))
                        .cachePublic()
                        .immutable())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'")
                .body(bytes.get());
    }

    @ExceptionHandler(InvalidImageException.class)
    ResponseEntity<Map<String, String>> invalid(InvalidImageException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> tooLarge() {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(Map.of("error", "Image is too large (max 5 MB)"));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    ResponseEntity<Map<String, String>> missing(EntityNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Product not found"));
    }
}
