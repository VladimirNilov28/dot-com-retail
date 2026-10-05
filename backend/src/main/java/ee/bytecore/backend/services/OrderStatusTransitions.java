package ee.bytecore.backend.services;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import ee.bytecore.backend.enums.OrderStatus;

/**
 * Single source of truth for which {@link OrderStatus} transitions are
 * allowed. Same-status, backwards, and arbitrary-skip transitions are denied
 * by omission (they simply aren't in the target set for a given status).
 * Terminal statuses (COMPLETED, CANCELLED) map to an empty set. Cancellation
 * is only reachable from PENDING/PAID. SHIPPING begins fulfillment processing, so it (and beyond) can never
 * transition to CANCELLED.
 */
public final class OrderStatusTransitions {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = new EnumMap<>(OrderStatus.class);

    static {
        ALLOWED.put(OrderStatus.PENDING, EnumSet.of(OrderStatus.PAID, OrderStatus.CANCELLED));
        ALLOWED.put(OrderStatus.PAID, EnumSet.of(OrderStatus.SHIPPING, OrderStatus.CANCELLED));
        ALLOWED.put(OrderStatus.SHIPPING, EnumSet.of(OrderStatus.COMPLETED));
        ALLOWED.put(OrderStatus.COMPLETED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED.put(OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class));
    }

    private OrderStatusTransitions() {}

    public static boolean canTransition(OrderStatus from, OrderStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }
}
