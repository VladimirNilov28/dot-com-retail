package ee.bytecore.backend.services;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.exceptions.ReviewPurchaseRequiredException;
import ee.bytecore.backend.repositories.product.ProductRatingRepository;
import ee.bytecore.backend.repositories.product.ProductRatingRepository.ReviewOwnership;
import ee.bytecore.backend.repositories.product.ProductRepository;
import ee.bytecore.backend.repositories.product.ProductReviewRow;
import ee.bytecore.backend.repositories.user.UserRepository;
import ee.bytecore.backend.services.audit.AuditService;
import ee.bytecore.backend.services.catalog.SearchPageInfo;
import ee.bytecore.backend.services.review.RatingSummary;
import ee.bytecore.backend.services.review.ReviewEligibility;
import ee.bytecore.backend.services.review.ReviewPage;
import ee.bytecore.backend.services.review.ReviewSortOption;

import jakarta.persistence.EntityNotFoundException;

/**
 * Star ratings and written reviews share one caller-owned row per user/product.
 * Reviews are post-moderated: visible immediately, staff may hide/restore them.
 * HIDDEN rows are excluded from public listings and from every product aggregate.
 * Creating a rating or written review requires a qualifying purchase
 * ({@link ProductRatingRepository#hasQualifyingPurchase}); editing the caller's existing
 * rating or written review does not, so pre-policy reviews stay editable and visible.
 */
@Service
public class ProductRatingService {
    public static final String VISIBLE = "VISIBLE";
    public static final String HIDDEN = "HIDDEN";
    public static final int MAX_PAGE_SIZE = 20;
    public static final int TITLE_MAX = 120;
    public static final int BODY_MIN = 10;
    public static final int BODY_MAX = 4000;
    public static final int REASON_MIN = 3;
    public static final int REASON_MAX = 500;

    // Tag-like markup (<b>, </p>, <!--, <?xml); a lone "<" or ">" such as "<3" or "a > b" stays allowed.
    private static final Pattern MARKUP = Pattern.compile("<\\s*[/!?]?\\s*[A-Za-z]|<!--|<\\?");
    // Control characters other than tab and newline.
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");

    private final ProductRatingRepository ratings;
    private final ProductRepository products;
    private final UserRepository users;
    private final AuditService audit;

    public ProductRatingService(
            ProductRatingRepository ratings, ProductRepository products, UserRepository users, AuditService audit) {
        this.ratings = ratings;
        this.products = products;
        this.users = users;
        this.audit = audit;
    }

    @Transactional
    public Product rate(Long currentUserId, Long productId, int stars) {
        if (currentUserId == null) {
            throw new AccessDeniedException("Active account is required");
        }
        validateStars(stars);
        requireActive(currentUserId);
        Product product = requireProduct(productId);
        if (ratings.findOwn(productId, currentUserId).isEmpty()) {
            requirePurchase(currentUserId, productId);
        }
        ratings.submit(product, currentUserId, stars);
        return product;
    }

    @Transactional
    public ProductReviewRow upsertMyReview(Long currentUserId, long productId, int stars, String title, String body) {
        if (currentUserId == null) {
            throw new AccessDeniedException("Active account is required");
        }
        validateStars(stars);
        String cleanTitle = plainText(title, "Review title", 1, TITLE_MAX, false);
        String cleanBody = plainText(body, "Review text", BODY_MIN, BODY_MAX, true);
        requireActive(currentUserId);
        requireProduct(productId);
        if (!hasWrittenReview(currentUserId, productId)) {
            requirePurchase(currentUserId, productId);
        }
        ratings.upsertReview(productId, currentUserId, stars, cleanTitle, cleanBody);
        return ratings.findOwn(productId, currentUserId).orElseThrow();
    }

    @Transactional
    public boolean deleteMyReview(Long currentUserId, long productId) {
        if (currentUserId == null) {
            throw new AccessDeniedException("Active account is required");
        }
        requireActive(currentUserId);
        return ratings.deleteOwn(productId, currentUserId);
    }

    @Transactional(readOnly = true)
    public Optional<ProductReviewRow> myReview(Long currentUserId, long productId) {
        if (currentUserId == null) {
            throw new AccessDeniedException("Active account is required");
        }
        return ratings.findOwn(productId, currentUserId);
    }

    /** Drives the storefront form: an existing written review can always be edited. */
    @Transactional(readOnly = true)
    public ReviewEligibility eligibility(Long currentUserId, long productId) {
        if (currentUserId == null) {
            throw new AccessDeniedException("Active account is required");
        }
        if (!products.existsById(productId)) {
            throw new EntityNotFoundException("Product with id " + productId + " not found");
        }
        if (hasWrittenReview(currentUserId, productId)) {
            return new ReviewEligibility(true, true, null);
        }
        return ratings.hasQualifyingPurchase(currentUserId, productId)
                ? new ReviewEligibility(true, false, null)
                : new ReviewEligibility(false, false, ReviewEligibility.PURCHASE_REQUIRED);
    }

    @Transactional
    public ProductReviewRow vote(Long currentUserId, long reviewId) {
        ReviewOwnership review = lockVotable(currentUserId, reviewId);
        if (Objects.equals(currentUserId, review.userId())) {
            throw new AccessDeniedException("You cannot vote on your own review");
        }
        ratings.vote(reviewId, currentUserId);
        return ratings.findById(reviewId, currentUserId).orElseThrow();
    }

    @Transactional
    public ProductReviewRow unvote(Long currentUserId, long reviewId) {
        lockVotable(currentUserId, reviewId);
        ratings.unvote(reviewId, currentUserId);
        return ratings.findById(reviewId, currentUserId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public ReviewPage productReviews(Long viewerId, long productId, ReviewSortOption sort, int page, int size) {
        validatePage(page, size);
        if (!products.existsById(productId)) {
            throw new EntityNotFoundException("Product with id " + productId + " not found");
        }
        List<ProductReviewRow> items = ratings.findVisibleWritten(
                productId, viewerId, sort == null ? ReviewSortOption.NEWEST : sort, page, size);
        long total = ratings.countVisibleWritten(productId);
        return new ReviewPage(items, pageInfo(page, size, total), summary(ratings.visibleDistribution(productId)));
    }

    @Transactional(readOnly = true)
    public ReviewPage moderationReviews(String status, int page, int size) {
        validatePage(page, size);
        if (status != null && !VISIBLE.equals(status) && !HIDDEN.equals(status)) {
            throw new IllegalArgumentException("Unknown review status");
        }
        List<ProductReviewRow> items = ratings.findForModeration(status, page, size);
        return new ReviewPage(items, pageInfo(page, size, ratings.countForModeration(status)), null);
    }

    @Transactional
    public ProductReviewRow hide(Long moderatorId, long reviewId, String reason) {
        String cleanReason = plainText(reason, "Moderation reason", REASON_MIN, REASON_MAX, false);
        ReviewOwnership review = ratings.lockForModeration(reviewId).orElseThrow(() -> notFound(reviewId));
        ratings.moderate(reviewId, HIDDEN, moderatorId, cleanReason);
        audit.record(
                "REVIEW_HIDE",
                "PRODUCT_REVIEW",
                reviewId,
                Map.of(
                        "productId",
                        review.productId(),
                        "previousStatus",
                        review.status(),
                        "status",
                        HIDDEN,
                        "reason",
                        cleanReason));
        return ratings.findById(reviewId, null).orElseThrow();
    }

    @Transactional
    public ProductReviewRow restore(Long moderatorId, long reviewId) {
        ReviewOwnership review = ratings.lockForModeration(reviewId).orElseThrow(() -> notFound(reviewId));
        ratings.moderate(reviewId, VISIBLE, moderatorId, null);
        audit.record(
                "REVIEW_RESTORE",
                "PRODUCT_REVIEW",
                reviewId,
                Map.of("productId", review.productId(), "previousStatus", review.status(), "status", VISIBLE));
        return ratings.findById(reviewId, null).orElseThrow();
    }

    /** Validates and normalizes plain text: trims, unifies newlines, rejects markup and control characters. */
    static String plainText(String value, String label, int min, int max, boolean multiline) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        String text = value.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (!multiline && text.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(label + " must be a single line");
        }
        if (CONTROL.matcher(text).find()) {
            throw new IllegalArgumentException(label + " contains unsupported characters");
        }
        if (MARKUP.matcher(text).find()) {
            throw new IllegalArgumentException(label + " must be plain text without HTML");
        }
        int length = text.codePointCount(0, text.length());
        if (length < min || length > max) {
            throw new IllegalArgumentException(label + " must be between " + min + " and " + max + " characters");
        }
        return text;
    }

    private ReviewOwnership lockVotable(Long currentUserId, long reviewId) {
        if (currentUserId == null) {
            throw new AccessDeniedException("Active account is required");
        }
        requireActive(currentUserId);
        ReviewOwnership review = ratings.lockForVote(reviewId).orElseThrow(() -> notFound(reviewId));
        // Hidden reviews and star-only ratings are not publicly listed, so they cannot be voted on.
        if (!VISIBLE.equals(review.status()) || !review.written()) {
            throw notFound(reviewId);
        }
        return review;
    }

    private boolean hasWrittenReview(long userId, long productId) {
        return ratings.findOwn(productId, userId).map(row -> row.body() != null).orElse(false);
    }

    private void requirePurchase(long userId, long productId) {
        if (!ratings.hasQualifyingPurchase(userId, productId)) {
            throw new ReviewPurchaseRequiredException();
        }
    }

    private void requireActive(Long currentUserId) {
        // Match self-service writes: lock the active owner against concurrent account deletion.
        users.findActiveByIdForUpdate(currentUserId)
                .orElseThrow(() -> new AccessDeniedException("Active account is required"));
    }

    private Product requireProduct(Long productId) {
        return products.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("Product with id " + productId + " not found"));
    }

    private static void validateStars(int stars) {
        if (stars < 1 || stars > 5) {
            throw new IllegalArgumentException("Rating stars must be between 1 and 5");
        }
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || page > 10_000) {
            throw new IllegalArgumentException("Page must be between 0 and 10000");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Page size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    private static SearchPageInfo pageInfo(int page, int size, long total) {
        return new SearchPageInfo(page, size, total, (int) ((total + size - 1) / size));
    }

    private static RatingSummary summary(Map<Integer, Integer> distribution) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        int count = 0;
        long sum = 0;
        for (int stars = 5; stars >= 1; stars--) {
            int n = distribution.getOrDefault(stars, 0);
            counts.put(stars, n);
            count += n;
            sum += (long) stars * n;
        }
        return new RatingSummary(count == 0 ? null : (double) sum / count, count, counts);
    }

    private static EntityNotFoundException notFound(long reviewId) {
        return new EntityNotFoundException("Review with id " + reviewId + " not found");
    }
}
