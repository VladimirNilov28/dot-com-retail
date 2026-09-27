package ee.bytecore.backend.graphql.datafetchers.wishlist;

import ee.bytecore.backend.graphql.mappers.WishlistMapper;
import ee.bytecore.backend.security.CurrentUserProvider;
import ee.bytecore.backend.services.WishlistService;

import com.netflix.dgs.codegen.generated.types.Wishlist;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;

@DgsComponent
public class WishlistQuery {

    private final WishlistService wishlistService;
    private final CurrentUserProvider currentUserProvider;

    public WishlistQuery(WishlistService wishlistService, CurrentUserProvider currentUserProvider) {
        this.wishlistService = wishlistService;
        this.currentUserProvider = currentUserProvider;
    }

    @DgsQuery
    public Wishlist myWishlist() {
        Long userId = currentUserProvider.getCurrentUserId();
        return WishlistMapper.toGraphQlType(wishlistService.getMyWishlist(userId));
    }
}
