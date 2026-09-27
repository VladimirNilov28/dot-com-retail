package ee.bytecore.backend.graphql.datafetchers.wishlist;

import ee.bytecore.backend.graphql.mappers.WishlistMapper;
import ee.bytecore.backend.security.CurrentUserProvider;
import ee.bytecore.backend.services.WishlistService;

import com.netflix.dgs.codegen.generated.types.AddWishlistItemInput;
import com.netflix.dgs.codegen.generated.types.WishlistItem;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;

@DgsComponent
public class WishlistMutation {

    private final WishlistService wishlistService;
    private final CurrentUserProvider currentUserProvider;

    public WishlistMutation(WishlistService wishlistService, CurrentUserProvider currentUserProvider) {
        this.wishlistService = wishlistService;
        this.currentUserProvider = currentUserProvider;
    }

    @DgsMutation
    public WishlistItem addWishlistItem(@InputArgument AddWishlistItemInput input) {
        Long userId = currentUserProvider.getCurrentUserId();
        return WishlistMapper.toGraphQlType(wishlistService.addItem(userId, Long.valueOf(input.getProductVariantId())));
    }

    @DgsMutation
    public Boolean removeWishlistItem(@InputArgument String wishlistItemId) {
        Long userId = currentUserProvider.getCurrentUserId();
        return wishlistService.removeItem(userId, Long.valueOf(wishlistItemId));
    }
}
