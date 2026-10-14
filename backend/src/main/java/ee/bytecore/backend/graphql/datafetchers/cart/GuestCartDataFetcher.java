package ee.bytecore.backend.graphql.datafetchers.cart;

import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;

import ee.bytecore.backend.exceptions.GuestCartUnavailableException;
import ee.bytecore.backend.graphql.mappers.CartMapper;
import ee.bytecore.backend.security.CurrentUserProvider;
import ee.bytecore.backend.security.GuestCartContext;
import ee.bytecore.backend.security.GuestCartCredentials;
import ee.bytecore.backend.services.CartService;

import com.netflix.dgs.codegen.generated.types.*;
import com.netflix.graphql.dgs.*;

@DgsComponent
public class GuestCartDataFetcher {
    private final CartService carts;
    private final CurrentUserProvider currentUser;

    public GuestCartDataFetcher(CartService carts, CurrentUserProvider currentUser) {
        this.carts = carts;
        this.currentUser = currentUser;
    }

    @DgsQuery
    public GuestCart guestCart(DgsDataFetchingEnvironment environment) {
        return CartMapper.toGuestGraphQlType(
                carts.getGuestCart(context(environment).credential()));
    }

    @DgsMutation
    public StartGuestCartResult startGuestCart(DgsDataFetchingEnvironment environment) {
        GuestCartContext context = context(environment);
        ee.bytecore.backend.entities.cart.Cart cart = null;
        if (context.credential() != null) {
            try {
                cart = carts.getGuestCart(context.credential());
            } catch (GuestCartUnavailableException unavailable) {
                // Only this explicit start operation may replace an unusable credential.
            }
        }
        boolean created = cart == null;
        if (created) {
            String credential = GuestCartCredentials.generate();
            cart = carts.createGuestCart(credential);
            context.issue(credential, cart.getGuestExpiresAt());
        }
        return StartGuestCartResult.newBuilder()
                .cart(CartMapper.toGuestGraphQlType(cart))
                .created(created)
                .build();
    }

    @DgsMutation
    public GuestCart addGuestCartItem(@InputArgument AddCartItemInput input, DgsDataFetchingEnvironment environment) {
        return CartMapper.toGuestGraphQlType(carts.addGuestItem(
                context(environment).credential(), parseId(input.getProductVariantId()), input.getQuantity()));
    }

    @DgsMutation
    public GuestCart updateGuestCartItem(
            @InputArgument String cartItemId,
            @InputArgument UpdateCartItemInput input,
            DgsDataFetchingEnvironment environment) {
        return CartMapper.toGuestGraphQlType(
                carts.updateGuestQuantity(context(environment).credential(), parseId(cartItemId), input.getQuantity()));
    }

    @DgsMutation
    public GuestCart removeGuestCartItem(@InputArgument String cartItemId, DgsDataFetchingEnvironment environment) {
        return CartMapper.toGuestGraphQlType(
                carts.removeGuestItem(context(environment).credential(), parseId(cartItemId)));
    }

    @DgsMutation
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).CART_READ) "
            + "and hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).CART_WRITE)")
    public GuestCartMergeResult mergeGuestCart(@InputArgument UUID requestId, DgsDataFetchingEnvironment environment) {
        GuestCartContext context = context(environment);
        var result = carts.mergeGuestCart(currentUser.getCurrentUserId(), context.credential(), requestId);
        if (!"BLOCKED".equals(result.status())) context.clear();
        return CartMapper.toGraphQlType(result);
    }

    private GuestCartContext context(DgsDataFetchingEnvironment environment) {
        GuestCartContext context = environment.getGraphQlContext().get(GuestCartContext.KEY);
        if (context == null) throw new AccessDeniedException("Guest cart requires the protected HTTP transport");
        return context;
    }

    private long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid cart item or product variant ID");
        }
    }
}
