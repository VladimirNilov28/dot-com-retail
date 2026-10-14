package ee.bytecore.backend.repositories.product;

import java.time.Instant;

/** One product_ratings row with its computed helpful count; title/body are null for star-only ratings. */
public record ProductReviewRow(
        long id,
        long productId,
        String productName,
        String productSlug,
        long userId,
        String authorName,
        int stars,
        String title,
        String body,
        String status,
        long helpfulCount,
        boolean viewerHasVoted,
        Instant createdAt,
        Instant updatedAt,
        String moderationReason,
        Instant moderatedAt) {}
