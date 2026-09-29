package ee.bytecore.backend.graphql.mappers;

import ee.bytecore.backend.entities.payment.Order;
import ee.bytecore.backend.entities.payment.OrderItem;

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

    private static com.netflix.dgs.codegen.generated.types.OrderStatus mapStatus(
            ee.bytecore.backend.enums.OrderStatus status) {
        return status == null ? null : com.netflix.dgs.codegen.generated.types.OrderStatus.valueOf(status.name());
    }
}
