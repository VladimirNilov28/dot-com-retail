package ee.bytecore.backend.graphql.datafetchers.cart;

import org.springframework.security.access.prepost.PreAuthorize;

import ee.bytecore.backend.graphql.mappers.CartMapper;
import ee.bytecore.backend.security.CurrentUserProvider;
import ee.bytecore.backend.services.CartService;

import com.netflix.dgs.codegen.generated.types.Cart;
import com.netflix.graphql.dgs.DgsComponent;
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
}
