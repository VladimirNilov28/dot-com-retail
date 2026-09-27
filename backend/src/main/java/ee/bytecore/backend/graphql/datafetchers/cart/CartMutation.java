package ee.bytecore.backend.graphql.datafetchers.cart;

import ee.bytecore.backend.graphql.mappers.CartMapper;
import ee.bytecore.backend.security.CurrentUserProvider;
import ee.bytecore.backend.services.CartService;

import com.netflix.dgs.codegen.generated.types.AddCartItemInput;
import com.netflix.dgs.codegen.generated.types.CartItem;
import com.netflix.dgs.codegen.generated.types.UpdateCartItemInput;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;

@DgsComponent
public class CartMutation {

    private final CartService cartService;
    private final CurrentUserProvider currentUserProvider;

    public CartMutation(CartService cartService, CurrentUserProvider currentUserProvider) {
        this.cartService = cartService;
        this.currentUserProvider = currentUserProvider;
    }

    @DgsMutation
    public CartItem addCartItem(@InputArgument AddCartItemInput input) {
        Long userId = currentUserProvider.getCurrentUserId();
        return CartMapper.toGraphQlType(
                cartService.addItem(userId, Long.valueOf(input.getProductVariantId()), input.getQuantity()));
    }

    @DgsMutation
    public CartItem updateCartItem(@InputArgument String cartItemId, @InputArgument UpdateCartItemInput input) {
        Long userId = currentUserProvider.getCurrentUserId();
        return CartMapper.toGraphQlType(
                cartService.updateItemQuantity(userId, Long.valueOf(cartItemId), input.getQuantity()));
    }

    @DgsMutation
    public Boolean removeCartItem(@InputArgument String cartItemId) {
        Long userId = currentUserProvider.getCurrentUserId();
        return cartService.removeItem(userId, Long.valueOf(cartItemId));
    }

    @DgsMutation
    public Boolean clearCart() {
        Long userId = currentUserProvider.getCurrentUserId();
        return cartService.clear(userId);
    }
}
