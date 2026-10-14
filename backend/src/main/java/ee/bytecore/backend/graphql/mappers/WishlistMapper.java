package ee.bytecore.backend.graphql.mappers;

import java.util.List;

import ee.bytecore.backend.entities.wishlist.Wishlist;
import ee.bytecore.backend.entities.wishlist.WishlistItem;

public class WishlistMapper {

    public static com.netflix.dgs.codegen.generated.types.Wishlist toGraphQlType(Wishlist entity) {
        if (entity == null) {
            return null;
        }
        return com.netflix.dgs.codegen.generated.types.Wishlist.newBuilder()
                .id(entity.getId().toString())
                .user(UserMapper.toGraphQlType(entity.getUser()))
                .items(
                        entity.getItems() == null
                                ? List.of()
                                : entity.getItems().stream()
                                        .map(WishlistMapper::toGraphQlType)
                                        .toList())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    public static com.netflix.dgs.codegen.generated.types.WishlistItem toGraphQlType(WishlistItem entity) {
        if (entity == null) {
            return null;
        }
        return com.netflix.dgs.codegen.generated.types.WishlistItem.newBuilder()
                .id(entity.getId().toString())
                .productVariant(ProductMapper.toGraphQlType(entity.getProductVariant()))
                .addedAt(entity.getAddedAt())
                .build();
    }
}
