package ee.bytecore.backend.graphql.datafetchers.order;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;

import ee.bytecore.backend.config.CheckoutSettings;
import ee.bytecore.backend.graphql.mappers.CheckoutMapper;
import ee.bytecore.backend.security.CurrentUserProvider;
import ee.bytecore.backend.security.GuestCartContext;
import ee.bytecore.backend.services.CheckoutValues.*;
import ee.bytecore.backend.services.OrderService;

import com.netflix.dgs.codegen.generated.types.CheckoutInput;
import com.netflix.dgs.codegen.generated.types.PlaceOrderInput;
import com.netflix.graphql.dgs.*;

@DgsComponent
public class CheckoutDataFetcher {
    private final OrderService orders;
    private final CurrentUserProvider currentUser;
    private final CheckoutSettings settings;

    public CheckoutDataFetcher(OrderService orders, CurrentUserProvider currentUser, CheckoutSettings settings) {
        this.orders = orders;
        this.currentUser = currentUser;
        this.settings = settings;
    }

    @DgsQuery
    public List<ShippingOption> checkoutShippingOptions(@InputArgument String countryCode) {
        return orders.shippingOptions(countryCode);
    }

    @DgsQuery
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).CART_READ) "
            + "and hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).ORDER_WRITE)")
    public Preview checkoutPreview(@InputArgument CheckoutInput input) {
        return orders.previewCheckout(userId(), null, CheckoutMapper.selection(input));
    }

    @DgsQuery
    public Preview guestCheckoutPreview(@InputArgument CheckoutInput input, DgsDataFetchingEnvironment environment) {
        GuestCartContext context = context(environment);
        Preview preview = orders.previewCheckout(null, context.credential(), CheckoutMapper.selection(input));
        context.prepareOrderCredential(settings.getGuestOrderTtl());
        return preview;
    }

    @DgsMutation
    public Confirmation createGuestOrder(@InputArgument PlaceOrderInput input, DgsDataFetchingEnvironment environment) {
        GuestCartContext context = context(environment);
        var order = orders.placeCheckout(
                null, context.credential(), context.orderCredential(), CheckoutMapper.placement(input));
        context.clear();
        context.prepareOrderCredential(settings.getGuestOrderTtl());
        return orders.confirmation(order);
    }

    @DgsQuery
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).ORDER_READ)")
    public Confirmation checkoutOrder(@InputArgument UUID requestId) {
        return orders.checkoutOrder(requestId, userId());
    }

    @DgsQuery
    public Confirmation guestOrder(
            @InputArgument UUID publicId, @InputArgument UUID requestId, DgsDataFetchingEnvironment environment) {
        return orders.guestOrder(publicId, requestId, context(environment).orderCredential());
    }

    private Long userId() {
        Long id = currentUser.getCurrentUserId();
        if (id == null) throw new AccessDeniedException("Active account is required");
        return id;
    }

    private GuestCartContext context(DgsDataFetchingEnvironment environment) {
        GuestCartContext context = environment.getGraphQlContext().get(GuestCartContext.KEY);
        if (context == null) throw new AccessDeniedException("Guest checkout requires the protected HTTP transport");
        return context;
    }
}
