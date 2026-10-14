package ee.bytecore.backend.graphql.datafetchers.inventory;

import static ee.bytecore.backend.services.audit.AuditDetails.details;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.graphql.mappers.InventoryMapper;
import ee.bytecore.backend.services.InventoryService;
import ee.bytecore.backend.services.WarehouseService;
import ee.bytecore.backend.services.audit.AuditService;

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
    private final AuditService auditService;

    public InventoryMutation(
            WarehouseService warehouseService, InventoryService inventoryService, AuditService auditService) {
        this.warehouseService = warehouseService;
        this.inventoryService = inventoryService;
        this.auditService = auditService;
    }

    @DgsMutation
    @Transactional
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).WAREHOUSE_WRITE) && hasAnyRole('WAREHOUSE','ADMIN')")
    public Warehouse createWarehouse(@InputArgument CreateWarehouseInput input) {
        var created = warehouseService.create(input.getName(), input.getLocation());
        auditService.record(
                "createWarehouse",
                "WAREHOUSE",
                created.getId(),
                details("name", input.getName(), "location", input.getLocation()));
        return InventoryMapper.toGraphQlType(created);
    }

    @DgsMutation
    @Transactional
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).WAREHOUSE_WRITE) && hasAnyRole('WAREHOUSE','ADMIN')")
    public Warehouse updateWarehouse(@InputArgument String warehouseId, @InputArgument UpdateWarehouseInput input) {
        long id = parseId(warehouseId, "warehouse");
        var updated = warehouseService.update(id, input.getName(), input.getLocation());
        auditService.record(
                "updateWarehouse", "WAREHOUSE", id, details("name", input.getName(), "location", input.getLocation()));
        return InventoryMapper.toGraphQlType(updated);
    }

    @DgsMutation
    @Transactional
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).WAREHOUSE_WRITE) && hasAnyRole('WAREHOUSE','ADMIN')")
    public Boolean deleteWarehouse(@InputArgument String warehouseId) {
        long id = parseId(warehouseId, "warehouse");
        boolean deleted = warehouseService.deleteById(id);
        if (deleted) auditService.record("deleteWarehouse", "WAREHOUSE", id, details());
        return deleted;
    }

    @DgsMutation
    @Transactional
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).WAREHOUSE_WRITE) && hasAnyRole('WAREHOUSE','ADMIN')")
    public Inventory setInventory(@InputArgument SetInventoryInput input) {
        long variantId = parseId(input.getProductVariantId(), "productVariant");
        long warehouseId = parseId(input.getWarehouseId(), "warehouse");
        var inventory = inventoryService.setInventory(variantId, warehouseId, input.getQuantity());
        auditService.record(
                "setInventory",
                "INVENTORY",
                inventory.getId(),
                details("productVariantId", variantId, "warehouseId", warehouseId, "quantity", input.getQuantity()));
        return InventoryMapper.toGraphQlType(inventory);
    }

    private long parseId(String rawId, String entityName) {
        try {
            return Long.parseLong(rawId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(String.format("Invalid %s id: %s", entityName, rawId));
        }
    }
}
