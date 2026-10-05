package ee.bytecore.backend.exceptions;

/**
 * Thrown when no single inventory row can fulfil a requested quantity for a
 * product variant. Split fulfillment across multiple warehouses is
 * intentionally out of scope, so this is also thrown when the sum across
 * warehouses would be sufficient but no individual row is.
 */
public class InsufficientStockException extends RuntimeException {

    private final Long productVariantId;
    private final int requestedQuantity;
    private final int availableQuantity;

    public InsufficientStockException(Long productVariantId, int requestedQuantity, int availableQuantity) {
        super(String.format(
                "Insufficient stock for product variant %s: requested %d, available %d",
                productVariantId, requestedQuantity, availableQuantity));
        this.productVariantId = productVariantId;
        this.requestedQuantity = requestedQuantity;
        this.availableQuantity = availableQuantity;
    }

    public Long getProductVariantId() {
        return productVariantId;
    }

    public int getRequestedQuantity() {
        return requestedQuantity;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }
}
