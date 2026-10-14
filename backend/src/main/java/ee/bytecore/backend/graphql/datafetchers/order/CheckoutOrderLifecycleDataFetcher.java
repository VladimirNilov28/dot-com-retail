package ee.bytecore.backend.graphql.datafetchers.order;

import java.util.List;

import ee.bytecore.backend.graphql.mappers.OrderMapper;
import ee.bytecore.backend.services.CheckoutValues.Confirmation;
import ee.bytecore.backend.services.OrderService;

import com.netflix.dgs.codegen.generated.types.OrderStatusEvent;
import com.netflix.dgs.codegen.generated.types.OrderTracking;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;

/**
 * Lifecycle fields on {@code CheckoutOrder}. A {@link Confirmation} source is only ever produced by an
 * already-authorized path (owner receipt, guest order grant, or {@code Order.checkout}), so these
 * resolvers inherit that authorization instead of re-checking with credentials guests do not have.
 */
@DgsComponent
public class CheckoutOrderLifecycleDataFetcher {
    private final OrderService orderService;

    public CheckoutOrderLifecycleDataFetcher(OrderService orderService) {
        this.orderService = orderService;
    }

    @DgsData(parentType = "CheckoutOrder", field = "tracking")
    public OrderTracking tracking(DgsDataFetchingEnvironment env) {
        Confirmation source = env.getSource();
        return orderService
                .findByPublicId(source.publicId())
                .map(OrderMapper::toTracking)
                .orElse(null);
    }

    @DgsData(parentType = "CheckoutOrder", field = "statusHistory")
    public List<OrderStatusEvent> statusHistory(DgsDataFetchingEnvironment env) {
        Confirmation source = env.getSource();
        return orderService
                .findByPublicId(source.publicId())
                .map(order -> orderService.statusHistory(order.getId()).stream()
                        .map(OrderMapper::toGraphQlType)
                        .toList())
                .orElse(List.of());
    }
}
