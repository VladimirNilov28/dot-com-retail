package ee.bytecore.backend.graphql.datafetchers.inventory;

import org.springframework.security.access.prepost.PreAuthorize;

import ee.bytecore.backend.graphql.mappers.InventoryMapper;
import ee.bytecore.backend.services.InventoryService;
import ee.bytecore.backend.services.WarehouseService;

import com.netflix.dgs.codegen.generated.types.CreateWarehouseInput;
import com.netflix.dgs.codegen.generated.types.Inventory;
import com.netflix.dgs.codegen.generated.types.SetInventoryInput;
import com.netflix.dgs.codegen.generated.types.UpdateWarehouseInput;
import com.netflix.dgs.codegen.generated.types.Warehouse;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;

@DgsComponent
public class InventoryMutation {

    private final WarehouseService warehouseService;
    private final InventoryService inventoryService;

    public InventoryMutation(WarehouseService warehouseService, InventoryService inventoryService) {
        this.warehouseService = warehouseService;
        this.inventoryService = inventoryService;
    }

    @DgsMutation
    @PreAuthorize("hasAnyRole('WAREHOUSE','ADMIN')")
    public Warehouse createWarehouse(@InputArgument CreateWarehouseInput input) {
        return InventoryMapper.toGraphQlType(warehouseService.create(input.getName(), input.getLocation()));
    }

    @DgsMutation
    @PreAuthorize("hasAnyRole('WAREHOUSE','ADMIN')")
    public Warehouse updateWarehouse(@InputArgument String warehouseId, @InputArgument UpdateWarehouseInput input) {
        long id = parseId(warehouseId, "warehouse");
        return InventoryMapper.toGraphQlType(warehouseService.update(id, input.getName(), input.getLocation()));
    }

    @DgsMutation
    @PreAuthorize("hasAnyRole('WAREHOUSE','ADMIN')")
    public Boolean deleteWarehouse(@InputArgument String warehouseId) {
        return warehouseService.deleteById(parseId(warehouseId, "warehouse"));
    }

    @DgsMutation
    @PreAuthorize("hasAnyRole('WAREHOUSE','ADMIN')")
    public Inventory setInventory(@InputArgument SetInventoryInput input) {
        return InventoryMapper.toGraphQlType(inventoryService.setInventory(
                Long.valueOf(input.getProductVariantId()), Long.valueOf(input.getWarehouseId()), input.getQuantity()));
    }

    private long parseId(String rawId, String entityName) {
        try {
            return Long.parseLong(rawId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(String.format("Invalid %s id: %s", entityName, rawId));
        }
    }
}
