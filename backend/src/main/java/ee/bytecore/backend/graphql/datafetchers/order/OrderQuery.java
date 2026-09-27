package ee.bytecore.backend.graphql.datafetchers.order;

import java.util.List;
import java.util.UUID;

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
    public List<Order> myOrders() {
        Long userId = currentUserProvider.getCurrentUserId();
        return orderService.findMyOrders(userId).stream()
                .map(OrderMapper::toGraphQlType)
                .toList();
    }

    @DgsData(parentType = "Order")
    public List<OrderItem> items(DgsDataFetchingEnvironment dfe) {
        Order order = dfe.getSource();
        if (order == null) {
            return List.of();
        }
        return orderService.findItems(Long.valueOf(order.getId())).stream()
                .map(OrderMapper::toGraphQlType)
                .toList();
    }

    private boolean isStaff() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ORDER_MANAGER")
                        || a.getAuthority().equals("ROLE_ADMIN"));
    }
}
