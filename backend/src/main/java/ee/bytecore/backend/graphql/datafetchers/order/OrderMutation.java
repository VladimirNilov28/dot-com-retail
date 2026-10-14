package ee.bytecore.backend.graphql.datafetchers.order;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;

import ee.bytecore.backend.enums.OrderStatus;
import ee.bytecore.backend.graphql.mappers.CheckoutMapper;
import ee.bytecore.backend.graphql.mappers.OrderMapper;
import ee.bytecore.backend.security.CurrentUserProvider;
import ee.bytecore.backend.services.OrderService;
import ee.bytecore.backend.services.OrderStatusPublisher;

import com.netflix.dgs.codegen.generated.types.DecideRefundInput;
import com.netflix.dgs.codegen.generated.types.Order;
import com.netflix.dgs.codegen.generated.types.OrderRefundRequest;
import com.netflix.dgs.codegen.generated.types.PlaceOrderInput;
import com.netflix.dgs.codegen.generated.types.RequestRefundInput;
import com.netflix.dgs.codegen.generated.types.SetOrderTrackingInput;
import com.netflix.dgs.codegen.generated.types.UpdateOrderStatusInput;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsSubscription;
import com.netflix.graphql.dgs.InputArgument;
import jakarta.persistence.EntityNotFoundException;
import org.reactivestreams.Publisher;

@DgsComponent
public class OrderMutation {

    private final OrderService orderService;
    private final OrderStatusPublisher orderStatusPublisher;
    private final CurrentUserProvider currentUserProvider;

    public OrderMutation(
            OrderService orderService,
            OrderStatusPublisher orderStatusPublisher,
            CurrentUserProvider currentUserProvider) {
        this.orderService = orderService;
        this.orderStatusPublisher = orderStatusPublisher;
        this.currentUserProvider = currentUserProvider;
    }

    @DgsMutation
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).ORDER_WRITE)")
    public Order createOrder(@InputArgument PlaceOrderInput input) {
        Long userId = currentUserProvider.getCurrentUserId();
        if (userId == null)
            throw new org.springframework.security.access.AccessDeniedException("Active account is required");
        return OrderMapper.toGraphQlType(
                orderService.placeCheckout(userId, null, null, CheckoutMapper.placement(input)));
    }

    @DgsMutation
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).ORDER_MANAGE_STATUS) && hasAnyRole('ORDER_MANAGER','ADMIN')")
    public Order updateOrderStatus(@InputArgument String orderId, @InputArgument UpdateOrderStatusInput input) {
        long id = parseId(orderId, "order");
        OrderStatus status = OrderStatus.valueOf(input.getStatus().name());
        return OrderMapper.toGraphQlType(orderService.updateFulfillmentStatus(
                id, status, currentUserProvider.getCurrentUserId(), input.getNote()));
    }

    @DgsMutation
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).ORDER_MANAGE_STATUS) && hasAnyRole('ORDER_MANAGER','ADMIN')")
    public Order setOrderTracking(@InputArgument SetOrderTrackingInput input) {
        return OrderMapper.toGraphQlType(orderService.setTracking(
                input.getPublicId(),
                input.getCarrier(),
                input.getTrackingNumber(),
                input.getTrackingUrl(),
                currentUserProvider.getCurrentUserId()));
    }

    @DgsMutation
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).ORDER_WRITE)")
    public OrderRefundRequest requestRefund(@InputArgument RequestRefundInput input) {
        return OrderMapper.toGraphQlType(orderService.requestRefund(
                input.getPublicId(), input.getReason(), currentUserProvider.getCurrentUserId()));
    }

    @DgsMutation
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).ORDER_MANAGE_STATUS) && hasAnyRole('ORDER_MANAGER','ADMIN')")
    public OrderRefundRequest approveRefund(@InputArgument DecideRefundInput input) {
        return OrderMapper.toGraphQlType(orderService.approveRefund(
                input.getRequestId(), input.getNote(), currentUserProvider.getCurrentUserId()));
    }

    @DgsMutation
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).ORDER_MANAGE_STATUS) && hasAnyRole('ORDER_MANAGER','ADMIN')")
    public OrderRefundRequest rejectRefund(@InputArgument DecideRefundInput input) {
        return OrderMapper.toGraphQlType(orderService.rejectRefund(
                input.getRequestId(), input.getNote(), currentUserProvider.getCurrentUserId()));
    }

    @DgsSubscription
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).ORDER_READ)")
    public Publisher<Order> orderStatusChanged(@InputArgument String orderId) {
        long id = parseId(orderId, "order");
        ee.bytecore.backend.entities.payment.Order order =
                orderService.findById(id).orElseThrow(() -> new EntityNotFoundException("Order not found"));
        boolean isStaff = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ORDER_MANAGER")
                        || authority.getAuthority().equals("ROLE_ADMIN"));
        orderService.requireReadable(order, currentUserProvider.getCurrentUserId(), isStaff);
        return orderStatusPublisher.subscribeTo(id).map(OrderMapper::toGraphQlType);
    }

    private long parseId(String rawId, String entityName) {
        try {
            return Long.parseLong(rawId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(String.format("Invalid %s id: %s", entityName, rawId));
        }
    }
}
