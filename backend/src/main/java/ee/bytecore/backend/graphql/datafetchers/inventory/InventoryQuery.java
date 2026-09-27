package ee.bytecore.backend.graphql.datafetchers.inventory;

import java.util.List;

import ee.bytecore.backend.graphql.mappers.InventoryMapper;
import ee.bytecore.backend.services.InventoryService;
import ee.bytecore.backend.services.WarehouseService;

import com.netflix.dgs.codegen.generated.types.Inventory;
import com.netflix.dgs.codegen.generated.types.ProductVariant;
import com.netflix.dgs.codegen.generated.types.Warehouse;
import com.netflix.graphql.dgs.*;

@DgsComponent
public class InventoryQuery {

    private final WarehouseService warehouseService;
    private final InventoryService inventoryService;

    public InventoryQuery(WarehouseService warehouseService, InventoryService inventoryService) {
        this.warehouseService = warehouseService;
        this.inventoryService = inventoryService;
    }

    @DgsQuery
    public Warehouse warehouse(@InputArgument String id) {
        return warehouseService
                .findById(Long.valueOf(id))
                .map(InventoryMapper::toGraphQlType)
                .orElse(null);
    }

    @DgsQuery
    public List<Warehouse> warehouses() {
        return warehouseService.findAll().stream()
                .map(InventoryMapper::toGraphQlType)
                .toList();
    }

    @DgsData(parentType = "Warehouse")
    public List<Inventory> inventory(DgsDataFetchingEnvironment dfe) {
        Warehouse warehouse = dfe.getSource();
        if (warehouse == null) {
            return List.of();
        }
        return inventoryService.findAllByWarehouseId(Long.valueOf(warehouse.getId())).stream()
                .map(InventoryMapper::toGraphQlType)
                .toList();
    }

    @DgsData(parentType = "ProductVariant", field = "inventory")
    public List<Inventory> productVariantInventory(DgsDataFetchingEnvironment dfe) {
        ProductVariant variant = dfe.getSource();
        if (variant == null) {
            return List.of();
        }
        return inventoryService.findAllByProductVariantId(Long.valueOf(variant.getId())).stream()
                .map(InventoryMapper::toGraphQlType)
                .toList();
    }
}
