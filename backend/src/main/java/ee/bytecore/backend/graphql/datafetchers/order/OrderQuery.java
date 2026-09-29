package ee.bytecore.backend.graphql.datafetchers.order;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;

import ee.bytecore.backend.graphql.mappers.OrderMapper;
import ee.bytecore.backend.security.CurrentUserProvider;
import ee.bytecore.backend.services.OrderService;

import com.netflix.dgs.codegen.generated.types.Order;
import com.netflix.dgs.codegen.generated.types.OrderItem;
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
    public List<Order> myOrders() {
        Long userId = currentUserProvider.getCurrentUserId();
        return orderService.findMyOrders(userId).stream()
                .map(OrderMapper::toGraphQlType)
                .toList();
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
}
