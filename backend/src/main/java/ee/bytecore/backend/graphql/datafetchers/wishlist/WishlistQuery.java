package ee.bytecore.backend.graphql.datafetchers.wishlist;

import org.springframework.security.access.prepost.PreAuthorize;

import ee.bytecore.backend.graphql.mappers.WishlistMapper;
import ee.bytecore.backend.security.CurrentUserProvider;
import ee.bytecore.backend.services.WishlistService;

import com.netflix.dgs.codegen.generated.types.Wishlist;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
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
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).WISHLIST_READ)")
    public Wishlist myWishlist() {
        Long userId = currentUserProvider.getCurrentUserId();
        return WishlistMapper.toGraphQlType(wishlistService.getMyWishlist(userId));
    }

    /**
     * {@link WishlistMapper#toGraphQlType(ee.bytecore.backend.entities.wishlist.WishlistItem)}
     * deliberately never sets {@code wishlist} to avoid eagerly re-mapping
     * the parent Wishlist for every item; this resolves it only when a
     * query actually selects {@code WishlistItem.wishlist}. Re-loads via
     * {@link WishlistService#getOwnedWishlistItem} so ownership is
     * re-checked rather than trusting the parent's already-resolved data.
     */
    @DgsData(parentType = "WishlistItem", field = "wishlist")
    public Wishlist wishlistForWishlistItem(DgsDataFetchingEnvironment dfe) {
        com.netflix.dgs.codegen.generated.types.WishlistItem source = dfe.getSource();
        Long userId = currentUserProvider.getCurrentUserId();
        var item = wishlistService.getOwnedWishlistItem(userId, Long.valueOf(source.getId()));
        return WishlistMapper.toGraphQlType(item.getWishlist());
    }
}
