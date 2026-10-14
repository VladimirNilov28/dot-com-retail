package ee.bytecore.backend.graphql.datafetchers.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;

import com.netflix.dgs.codegen.generated.types.ProductAvailability;
import com.netflix.dgs.codegen.generated.types.ProductVariant;
import ee.bytecore.backend.services.InventoryService;

@Tag("unit")
class ProductAvailabilityQueryTest {

    private final InventoryService inventoryService = mock(InventoryService.class);
    private final ProductAvailabilityQuery query = new ProductAvailabilityQuery(inventoryService);

    private DgsDataFetchingEnvironment env(String variantId) {
        DgsDataFetchingEnvironment env = mock(DgsDataFetchingEnvironment.class);
        ProductVariant variant = new ProductVariant();
        variant.setId(variantId);
        when(env.getSource()).thenReturn(variant);
        return env;
    }

    @Test
    void shouldReportAvailableAndCapPurchasableLimit() {
        when(inventoryService.sellableQuantity(7L)).thenReturn(250);
        assertThat(query.availability(env("7"))).isEqualTo(ProductAvailability.AVAILABLE);
        assertThat(query.purchasableLimit(env("7"))).isEqualTo(10);
    }

    @Test
    void shouldReportOutOfStockWhenNoSingleRowHasStock() {
        when(inventoryService.sellableQuantity(8L)).thenReturn(0);
        assertThat(query.availability(env("8"))).isEqualTo(ProductAvailability.OUT_OF_STOCK);
        assertThat(query.purchasableLimit(env("8"))).isZero();
    }

    @Test
    void shouldReportSmallExactLimit() {
        when(inventoryService.sellableQuantity(9L)).thenReturn(3);
        assertThat(query.purchasableLimit(env("9"))).isEqualTo(3);
    }
}
