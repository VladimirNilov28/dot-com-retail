package ee.bytecore.backend.services.review;

/** Whether the caller may submit a review; reason is null when allowed. */
public record ReviewEligibility(boolean canReview, boolean hasReview, String reason) {
    public static final String PURCHASE_REQUIRED = "PURCHASE_REQUIRED";
}
