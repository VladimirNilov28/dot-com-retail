package ee.bytecore.backend.graphql.datafetchers.inventory;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.dgs.codegen.generated.types.ProductAvailability;
import com.netflix.dgs.codegen.generated.types.ProductVariant;
import ee.bytecore.backend.services.InventoryService;

/**
 * Public, warehouse-free availability. It mirrors the checkout allocation rule
 * (a single stock row must cover the whole quantity) so the storefront never
 * promises more than one order could be allocated. No stock is reserved here.
 */
@DgsComponent
public class ProductAvailabilityQuery {

    static final int PURCHASABLE_CAP = 10;

    private final InventoryService inventoryService;

    public ProductAvailabilityQuery(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @DgsData(parentType = "ProductVariant", field = "purchasableLimit")
    public Integer purchasableLimit(DgsDataFetchingEnvironment dfe) {
        ProductVariant variant = dfe.getSource();
        if (variant == null || variant.getId() == null) {
            return null;
        }
        return Math.min(inventoryService.sellableQuantity(Long.valueOf(variant.getId())), PURCHASABLE_CAP);
    }

    @DgsData(parentType = "ProductVariant", field = "availability")
    public ProductAvailability availability(DgsDataFetchingEnvironment dfe) {
        Integer limit = purchasableLimit(dfe);
        if (limit == null) {
            return null;
        }
        return limit > 0 ? ProductAvailability.AVAILABLE : ProductAvailability.OUT_OF_STOCK;
    }
}
