package ee.bytecore.backend.graphql.mappers;

import ee.bytecore.backend.entities.payment.Order;
import ee.bytecore.backend.entities.payment.OrderItem;
import ee.bytecore.backend.entities.payment.OrderStatusEvent;
import ee.bytecore.backend.entities.payment.RefundRequest;

public class OrderMapper {

    public static com.netflix.dgs.codegen.generated.types.Order toGraphQlType(Order entity) {
        if (entity == null) {
            return null;
        }
        return com.netflix.dgs.codegen.generated.types.Order.newBuilder()
                .id(entity.getId().toString())
                .publicId(entity.getPublicId())
                .user(UserMapper.toGraphQlType(entity.getUser()))
                .status(mapStatus(entity.getStatus()))
                .totalAmount(entity.getTotalAmount())
                .cancellationReason(entity.getCancellationReason())
                .tracking(toTracking(entity))
                .allowedStatusTransitions(allowedStatusTransitions(entity))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public static com.netflix.dgs.codegen.generated.types.OrderItem toGraphQlType(OrderItem entity) {
        if (entity == null) {
            return null;
        }
        return com.netflix.dgs.codegen.generated.types.OrderItem.newBuilder()
                .id(entity.getId().toString())
                .publicId(entity.getPublicId())
                .productVariant(ProductMapper.toGraphQlType(entity.getProductVariant()))
                .quantity(entity.getQuantity())
                .priceAtPurchase(entity.getPriceAtPurchase())
                .build();
    }

    public static com.netflix.dgs.codegen.generated.types.OrderTracking toTracking(Order entity) {
        if (entity == null || entity.getTrackingNumber() == null) return null;
        return com.netflix.dgs.codegen.generated.types.OrderTracking.newBuilder()
                .carrier(entity.getCarrier())
                .trackingNumber(entity.getTrackingNumber())
                .trackingUrl(entity.getTrackingUrl())
                .shippedAt(entity.getShippedAt())
                .build();
    }

    /** Staff fulfillment steps reachable from the current state; cancellation has its own operation. */
    static java.util.List<com.netflix.dgs.codegen.generated.types.OrderStatus> allowedStatusTransitions(Order entity) {
        if (entity.getStatus() == null || !"SUCCEEDED".equals(entity.getPaymentStatus())) return java.util.List.of();
        return ee.bytecore.backend.services.OrderStatusTransitions.allowedTargets(entity.getStatus()).stream()
                .filter(status -> status != ee.bytecore.backend.enums.OrderStatus.CANCELLED
                        && status != ee.bytecore.backend.enums.OrderStatus.PAID)
                .sorted()
                .map(OrderMapper::mapStatus)
                .toList();
    }

    public static com.netflix.dgs.codegen.generated.types.OrderStatusEvent toGraphQlType(OrderStatusEvent event) {
        return com.netflix.dgs.codegen.generated.types.OrderStatusEvent.newBuilder()
                .fromStatus(
                        event.getFromStatus() == null
                                ? null
                                : com.netflix.dgs.codegen.generated.types.OrderStatus.valueOf(event.getFromStatus()))
                .toStatus(com.netflix.dgs.codegen.generated.types.OrderStatus.valueOf(event.getToStatus()))
                .actor(com.netflix.dgs.codegen.generated.types.OrderStatusActor.valueOf(event.getActorKind()))
                .note(event.getNote())
                .occurredAt(event.getOccurredAt())
                .build();
    }

    public static com.netflix.dgs.codegen.generated.types.OrderRefundRequest toGraphQlType(RefundRequest request) {
        return com.netflix.dgs.codegen.generated.types.OrderRefundRequest.newBuilder()
                .id(request.getId())
                .status(com.netflix.dgs.codegen.generated.types.OrderRefundRequestStatus.valueOf(request.getStatus()))
                .reason(request.getReason())
                .decisionNote(request.getDecisionNote())
                .createdAt(request.getCreatedAt())
                .decidedAt(request.getDecidedAt())
                .build();
    }

    private static com.netflix.dgs.codegen.generated.types.OrderStatus mapStatus(
            ee.bytecore.backend.enums.OrderStatus status) {
        return status == null ? null : com.netflix.dgs.codegen.generated.types.OrderStatus.valueOf(status.name());
    }
}
