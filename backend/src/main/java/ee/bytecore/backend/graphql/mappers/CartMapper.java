package ee.bytecore.backend.graphql.mappers;

import java.util.List;

import ee.bytecore.backend.entities.cart.Cart;
import ee.bytecore.backend.entities.cart.CartItem;

public class CartMapper {

    public static com.netflix.dgs.codegen.generated.types.Cart toGraphQlType(Cart entity) {
        if (entity == null) {
            return null;
        }
        return com.netflix.dgs.codegen.generated.types.Cart.newBuilder()
                .id(entity.getId().toString())
                .user(UserMapper.toGraphQlType(entity.getUser()))
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
                .build();
    }
}
