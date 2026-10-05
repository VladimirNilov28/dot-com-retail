package ee.bytecore.backend.entities.cart;

public record CartMergeLine(Long productVariantId, int userQuantityBefore, int guestQuantityAdded, int finalQuantity) {}
