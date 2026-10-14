package ee.bytecore.backend.graphql.datafetchers.order;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;

import ee.bytecore.backend.graphql.mappers.OrderMapper;
import ee.bytecore.backend.security.CurrentUserProvider;
import ee.bytecore.backend.services.OrderService;

import com.netflix.dgs.codegen.generated.types.Order;
import com.netflix.dgs.codegen.generated.types.OrderFilter;
import com.netflix.dgs.codegen.generated.types.OrderItem;
import com.netflix.dgs.codegen.generated.types.OrderPage;
import com.netflix.dgs.codegen.generated.types.StaffOrderFilter;
import com.netflix.graphql.dgs.*;

@DgsComponent
public class OrderQuery {

    private final OrderService orderService;
    private final CurrentUserProvider currentUserProvider;

    public OrderQuery(OrderService orderService, CurrentUserProvider currentUserProvider) {
        this.orderService = orderService;
        this.currentUserProvider = currentUserProvider;
    }

    @DgsQuery
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).ORDER_READ)")
    public Order order(@InputArgument String id, @InputArgument UUID publicId) {
        ee.bytecore.backend.entities.payment.Order order = publicId != null
                ? orderService.findByPublicId(publicId).orElse(null)
                : id != null ? orderService.findById(Long.valueOf(id)).orElse(null) : null;

        if (order == null) {
            return null;
        }
        orderService.requireReadable(order, currentUserProvider.getCurrentUserId(), isStaff());
        return OrderMapper.toGraphQlType(order);
    }

    @DgsQuery
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).ORDER_READ)")
    public OrderPage myOrders(
            @InputArgument OrderFilter filter, @InputArgument Integer page, @InputArgument Integer size) {
        var result = orderService.myOrders(
                currentUserProvider.getCurrentUserId(),
                filter == null
                        ? null
                        : new OrderService.OrderFilter(
                                status(filter.getStatus()), filter.getFrom(), filter.getTo(), null),
                page,
                size);
        return page(result);
    }

    @DgsQuery
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).ORDER_READ) && hasAnyRole('ORDER_MANAGER','ADMIN')")
    public OrderPage staffOrders(
            @InputArgument StaffOrderFilter filter, @InputArgument Integer page, @InputArgument Integer size) {
        var result = orderService.staffOrders(
                filter == null
                        ? null
                        : new OrderService.OrderFilter(
                                status(filter.getStatus()), filter.getFrom(), filter.getTo(), filter.getQuery()),
                page,
                size);
        return page(result);
    }

    private static OrderPage page(OrderService.OrderPage result) {
        return OrderPage.newBuilder()
                .items(result.items().stream().map(OrderMapper::toGraphQlType).toList())
                .totalCount(Math.toIntExact(Math.min(Integer.MAX_VALUE, result.totalCount())))
                .page(result.page())
                .size(result.size())
                .build();
    }

    private static ee.bytecore.backend.enums.OrderStatus status(
            com.netflix.dgs.codegen.generated.types.OrderStatus status) {
        return status == null ? null : ee.bytecore.backend.enums.OrderStatus.valueOf(status.name());
    }

    @DgsData(parentType = "Order", field = "statusHistory")
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).ORDER_READ)")
    public List<com.netflix.dgs.codegen.generated.types.OrderStatusEvent> statusHistory(
            DgsDataFetchingEnvironment env) {
        var order = readable(env);
        return orderService.statusHistory(order.getId()).stream()
                .map(OrderMapper::toGraphQlType)
                .toList();
    }

    @DgsData(parentType = "Order", field = "refundRequests")
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).ORDER_READ)")
    public List<com.netflix.dgs.codegen.generated.types.OrderRefundRequest> refundRequests(
            DgsDataFetchingEnvironment env) {
        var order = readable(env);
        return orderService.refundRequests(order.getId()).stream()
                .map(OrderMapper::toGraphQlType)
                .toList();
    }

    @DgsData(parentType = "Order", field = "refundEligibility")
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).ORDER_READ)")
    public ee.bytecore.backend.services.OrderRecoveryValues.Eligibility refundEligibility(
            DgsDataFetchingEnvironment env) {
        return orderService.refundEligibility(readable(env));
    }

    private ee.bytecore.backend.entities.payment.Order readable(DgsDataFetchingEnvironment env) {
        Order source = env.getSource();
        var order = orderService
                .findById(Long.valueOf(source.getId()))
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Order not found"));
        return orderService.requireReadable(order, currentUserProvider.getCurrentUserId(), isStaff());
    }

    @DgsData(parentType = "Order")
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).ORDER_READ)")
    public List<OrderItem> items(DgsDataFetchingEnvironment dfe) {
        Order order = dfe.getSource();
        if (order == null) {
            return List.of();
        }
        return orderService.findItems(Long.valueOf(order.getId())).stream()
                .map(OrderMapper::toGraphQlType)
                .toList();
    }

    /**
     * {@link OrderMapper#toGraphQlType(ee.bytecore.backend.entities.payment.OrderItem)}
     * deliberately never sets {@code order} to avoid eagerly re-mapping the
     * parent Order for every item; this resolves it only when a query
     * actually selects {@code OrderItem.order}. Re-loads via
     * {@link OrderService#getOwnedOrderItem} so read access is re-checked
     * rather than trusting the parent's already-resolved data.
     */
    @DgsData(parentType = "OrderItem", field = "order")
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).ORDER_READ)")
    public Order orderForOrderItem(DgsDataFetchingEnvironment dfe) {
        OrderItem source = dfe.getSource();
        ee.bytecore.backend.entities.payment.OrderItem item = orderService.getOwnedOrderItem(
                Long.valueOf(source.getId()), currentUserProvider.getCurrentUserId(), isStaff());
        return OrderMapper.toGraphQlType(item.getOrder());
    }

    private boolean isStaff() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ORDER_MANAGER")
                        || a.getAuthority().equals("ROLE_ADMIN"));
    }

    @DgsData(parentType = "Order", field = "checkout")
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).ORDER_READ)")
    public ee.bytecore.backend.services.CheckoutValues.Confirmation checkout(DgsDataFetchingEnvironment environment) {
        Order source = environment.getSource();
        var order = orderService.findById(Long.valueOf(source.getId())).orElseThrow();
        orderService.requireReadable(order, currentUserProvider.getCurrentUserId(), isStaff());
        return orderService.confirmation(order);
    }

    @DgsData(parentType = "OrderItem", field = "snapshot")
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).ORDER_READ)")
    public ee.bytecore.backend.services.CheckoutValues.Line snapshot(DgsDataFetchingEnvironment environment) {
        OrderItem source = environment.getSource();
        return orderService
                .getOwnedOrderItem(Long.valueOf(source.getId()), currentUserProvider.getCurrentUserId(), isStaff())
                .getCheckoutSnapshot();
    }

    @DgsData(parentType = "Order", field = "cancellationEligibility")
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).ORDER_READ)")
    public ee.bytecore.backend.services.OrderRecoveryValues.Eligibility cancellationEligibility(
            DgsDataFetchingEnvironment env) {
        return recovery(env).cancellationEligibility();
    }

    @DgsData(parentType = "Order", field = "cancellation")
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).ORDER_READ)")
    public ee.bytecore.backend.services.OrderRecoveryValues.Cancellation cancellation(DgsDataFetchingEnvironment env) {
        return recovery(env).cancellation();
    }

    @DgsData(parentType = "Order", field = "payment")
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).ORDER_READ)")
    public ee.bytecore.backend.services.OrderRecoveryValues.Payment payment(DgsDataFetchingEnvironment env) {
        return recovery(env).payment();
    }

    @DgsData(parentType = "Order", field = "refund")
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).ORDER_READ)")
    public ee.bytecore.backend.services.OrderRecoveryValues.Refund refund(DgsDataFetchingEnvironment env) {
        return recovery(env).refund();
    }

    private ee.bytecore.backend.services.OrderRecoveryValues.Summary recovery(DgsDataFetchingEnvironment env) {
        Order source = env.getSource();
        return orderService.recovery(Long.valueOf(source.getId()), currentUserProvider.getCurrentUserId(), isStaff());
    }
}
