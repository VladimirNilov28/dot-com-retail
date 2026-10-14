package ee.bytecore.backend.entities.product;

import java.time.Instant;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/** Image metadata only; the bytes live in runtime media storage under {@code storageKey}. */
@Getter
@Entity
@Table(name = "product_images")
public class ProductImage {
    protected ProductImage() {}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "storage_key", nullable = false, updatable = false, length = 80)
    private String storageKey;

    @Column(name = "content_type", nullable = false, updatable = false, length = 40)
    private String contentType;

    @Column(name = "byte_size", nullable = false, updatable = false)
    private Integer byteSize;

    @Column(name = "width", nullable = false, updatable = false)
    private Integer width;

    @Column(name = "height", nullable = false, updatable = false)
    private Integer height;

    @Setter
    @Column(name = "alt_text", nullable = false, length = 200)
    private String altText;

    @Setter
    @Column(name = "position", nullable = false)
    private Integer position;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public static ProductImage create(
            Long productId,
            String storageKey,
            String contentType,
            int byteSize,
            int width,
            int height,
            String altText,
            int position) {
        ProductImage image = new ProductImage();
        image.productId = productId;
        image.storageKey = storageKey;
        image.contentType = contentType;
        image.byteSize = byteSize;
        image.width = width;
        image.height = height;
        image.altText = altText;
        image.position = position;
        return image;
    }

    public String url() {
        return "/media/products/" + storageKey;
    }
}
