package ee.bytecore.backend.graphql.mappers;

import ee.bytecore.backend.entities.inventory.Inventory;
import ee.bytecore.backend.entities.inventory.Warehouse;

public class InventoryMapper {

    public static com.netflix.dgs.codegen.generated.types.Warehouse toGraphQlType(Warehouse entity) {
        if (entity == null) {
            return null;
        }
        return com.netflix.dgs.codegen.generated.types.Warehouse.newBuilder()
                .id(entity.getId().toString())
                .name(entity.getName())
                .location(entity.getLocation())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public static com.netflix.dgs.codegen.generated.types.Inventory toGraphQlType(Inventory entity) {
        if (entity == null) {
            return null;
        }
        return com.netflix.dgs.codegen.generated.types.Inventory.newBuilder()
                .id(entity.getId().toString())
                .productVariant(ProductMapper.toGraphQlType(entity.getProductVariant()))
                .warehouse(toGraphQlType(entity.getWarehouse()))
                .quantity(entity.getQuantity())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
