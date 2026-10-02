package ee.bytecore.backend.graphql.datafetchers.cart;

import org.springframework.security.access.prepost.PreAuthorize;

import ee.bytecore.backend.graphql.mappers.CartMapper;
import ee.bytecore.backend.security.CurrentUserProvider;
import ee.bytecore.backend.services.CartService;

import com.netflix.dgs.codegen.generated.types.Cart;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.DgsQuery;

@DgsComponent
public class CartQuery {

    private final CartService cartService;
    private final CurrentUserProvider currentUserProvider;

    public CartQuery(CartService cartService, CurrentUserProvider currentUserProvider) {
        this.cartService = cartService;
        this.currentUserProvider = currentUserProvider;
    }

    @DgsQuery
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).CART_READ)")
    public Cart myCart() {
        Long userId = currentUserProvider.getCurrentUserId();
        return CartMapper.toGraphQlType(cartService.getMyCart(userId));
    }

    /**
     * {@link CartMapper#toGraphQlType(ee.bytecore.backend.entities.cart.CartItem)}
     * deliberately never sets {@code cart} to avoid eagerly re-mapping the
     * parent Cart for every item; this resolves it only when a query
     * actually selects {@code CartItem.cart}. Re-loads via
     * {@link CartService#getOwnedCartItem} so ownership is re-checked rather
     * than trusting the parent's already-resolved data.
     */
    @DgsData(parentType = "CartItem", field = "cart")
    public Cart cartForCartItem(DgsDataFetchingEnvironment dfe) {
        com.netflix.dgs.codegen.generated.types.CartItem source = dfe.getSource();
        Long userId = currentUserProvider.getCurrentUserId();
        var item = cartService.getOwnedCartItem(userId, Long.valueOf(source.getId()));
        return CartMapper.toGraphQlType(item.getCart());
    }
}
