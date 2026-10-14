package ee.bytecore.backend.graphql.mappers;

import java.util.List;

import ee.bytecore.backend.entities.cart.Cart;
import ee.bytecore.backend.entities.cart.CartItem;
import ee.bytecore.backend.services.CartService;

import com.netflix.dgs.codegen.generated.types.*;

public class CartMapper {

    public static com.netflix.dgs.codegen.generated.types.Cart toGraphQlType(Cart entity) {
        if (entity == null) {
            return null;
        }
        return com.netflix.dgs.codegen.generated.types.Cart.newBuilder()
                .id(entity.getId().toString())
                .user(UserMapper.toGraphQlType(entity.getUser()))
                .totals(totals(entity))
                .items(
                        entity.getItems() == null
                                ? List.of()
                                : entity.getItems().stream()
                                        .map(CartMapper::toGraphQlType)
                                        .toList())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public static com.netflix.dgs.codegen.generated.types.CartItem toGraphQlType(CartItem entity) {
        if (entity == null) {
            return null;
        }
        return com.netflix.dgs.codegen.generated.types.CartItem.newBuilder()
                .id(entity.getId().toString())
                .productVariant(ProductMapper.toGraphQlType(entity.getProductVariant()))
                .quantity(entity.getQuantity())
                .subtotal(CartService.lineSubtotal(entity))
                .build();
    }

    private static CartTotals totals(Cart entity) {
        return CartTotals.newBuilder()
                .subtotal(CartService.subtotal(entity))
                .currency("EUR")
                .build();
    }

    public static GuestCart toGuestGraphQlType(Cart entity) {
        if (entity == null) return null;
        return GuestCart.newBuilder()
                .id(entity.getId().toString())
                .expiresAt(entity.getGuestExpiresAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .totals(totals(entity))
                .items(entity.getItems().stream()
                        .map(item -> GuestCartItem.newBuilder()
                                .id(item.getId().toString())
                                .quantity(item.getQuantity())
                                .productVariant(ProductMapper.toGraphQlType(item.getProductVariant()))
                                .subtotal(CartService.lineSubtotal(item))
                                .build())
                        .toList())
                .build();
    }

    public static GuestCartMergeResult toGraphQlType(CartService.GuestMergeResult result) {
        return GuestCartMergeResult.newBuilder()
                .status(GuestCartMergeStatus.valueOf(result.status()))
                .cart(toGraphQlType(result.cart()))
                .mergedItems(result.mergedItems().stream()
                        .map(line -> GuestCartMergedItem.newBuilder()
                                .productVariantId(line.productVariantId().toString())
                                .userQuantityBefore(line.userQuantityBefore())
                                .guestQuantityAdded(line.guestQuantityAdded())
                                .finalQuantity(line.finalQuantity())
                                .build())
                        .toList())
                .conflicts(result.conflicts().stream()
                        .map(conflict -> GuestCartMergeConflict.newBuilder()
                                .productVariantId(conflict.productVariantId().toString())
                                .code(GuestCartMergeConflictCode.valueOf(conflict.code()))
                                .userQuantity(conflict.userQuantity())
                                .guestQuantity(conflict.guestQuantity())
                                .message(conflict.message())
                                .build())
                        .toList())
                .build();
    }
}
