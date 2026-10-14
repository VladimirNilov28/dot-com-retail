package ee.bytecore.backend.exceptions;

/** The caller has no qualifying purchase of the product, so may not create a review or rating. */
public class ReviewPurchaseRequiredException extends RuntimeException {
    public static final String CODE = "REVIEW_PURCHASE_REQUIRED";

    public ReviewPurchaseRequiredException() {
        super("Only customers who purchased this product can review it");
    }
}
