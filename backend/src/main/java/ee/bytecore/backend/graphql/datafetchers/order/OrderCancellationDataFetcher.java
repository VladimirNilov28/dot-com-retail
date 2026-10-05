package ee.bytecore.backend.graphql.datafetchers.order;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;

import ee.bytecore.backend.security.CurrentUserProvider;
import ee.bytecore.backend.security.GuestCartContext;
import ee.bytecore.backend.services.OrderRecoveryValues;
import ee.bytecore.backend.services.OrderService;

import com.netflix.dgs.codegen.generated.types.CancelOrderInput;
import com.netflix.graphql.dgs.*;

@DgsComponent
public class OrderCancellationDataFetcher {
    private final OrderService orders;
    private final CurrentUserProvider users;

    public OrderCancellationDataFetcher(OrderService orders, CurrentUserProvider users) {
        this.orders = orders;
        this.users = users;
    }

    @DgsMutation
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).ORDER_WRITE)")
    public OrderRecoveryValues.Payload cancelOrder(@InputArgument CancelOrderInput input) {
        return orders.cancelOrder(input.getRequestId(), input.getPublicId(), userId(), null, false);
    }

    @DgsMutation
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).ORDER_MANAGE_STATUS) && hasAnyRole('ORDER_MANAGER','ADMIN')")
    public OrderRecoveryValues.Payload cancelOrderAsStaff(@InputArgument CancelOrderInput input) {
        return orders.cancelOrder(input.getRequestId(), input.getPublicId(), userId(), null, true);
    }

    @DgsMutation
    public OrderRecoveryValues.Payload cancelGuestOrder(
            @InputArgument CancelOrderInput input, DgsDataFetchingEnvironment environment) {
        GuestCartContext context = environment.getGraphQlContext().get(GuestCartContext.KEY);
        if (context == null) throw new AccessDeniedException("Guest cancellation requires protected HTTP transport");
        return orders.cancelOrder(input.getRequestId(), input.getPublicId(), null, context.orderCredential(), false);
    }

    private Long userId() {
        Long id = users.getCurrentUserId();
        if (id == null) throw new AccessDeniedException("Active account is required");
        return id;
    }
}
