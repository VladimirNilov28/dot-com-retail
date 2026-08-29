package ee.bytecore.backend.graphql.datafetchers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ee.bytecore.backend.entities.inventory.Inventory;
import ee.bytecore.backend.entities.inventory.Warehouse;
import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.graphql.datafetchers.inventory.InventoryDataFetcher;
import ee.bytecore.backend.graphql.scalars.GraphQLConfig;
import ee.bytecore.backend.graphql.scalars.InstantScalar;
import ee.bytecore.backend.graphql.scalars.LocalDateScalar;
import ee.bytecore.backend.repositories.inventory.InventoryRepository;
import ee.bytecore.backend.repositories.inventory.WarehouseRepository;
import ee.bytecore.backend.repositories.product.ProductRepository;
import ee.bytecore.backend.repositories.product.ProductVariantRepository;

import com.netflix.graphql.dgs.test.EnableDgsMockMvcTest;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@SpringBootTest(classes = {InventoryDataFetcher.class, GraphQLConfig.class, LocalDateScalar.class, InstantScalar.class})
@EnableDgsMockMvcTest
@AutoConfigureHttpGraphQlTester
@Tag("graphql")
class InventoryDataFetcherTest {

    @MockitoBean
    WarehouseRepository warehouseRepository;

    @MockitoBean
    InventoryRepository inventoryRepository;

    @MockitoBean
    ProductVariantRepository productVariantRepository;

    @MockitoBean
    ProductRepository productRepository;

    private Warehouse warehouse;
    private Product product;
    private ProductVariant productVariant;
    private Inventory inventory;

    @Autowired
    private GraphQlTester graphQlTester;

    @BeforeEach
    void setUp() {
        warehouse = Warehouse.create("Main Warehouse", "Tallinn");
        product = Product.create("T-Shirt", "t-shirt", "A plain t-shirt");
        productVariant = ProductVariant.create(product, "TSHIRT-M-BLACK", new BigDecimal("19.99"));
        inventory = Inventory.create(productVariant, warehouse, 10);
    }

    @Test
    @WithMockUser
    void shouldReturnWarehouseByIdTest() {
        Long id = warehouse.getId();
        when(warehouseRepository.findById(id)).thenReturn(Optional.of(warehouse));

        @Language("GraphQl")
        var query =
                """
            query($id: ID!) {
              warehouse(id: $id) {
                name
                location
              }
            }
        """;

        graphQlTester
                .document(query)
                .variable("id", id)
                .execute()
                .path("warehouse.name")
                .entity(String.class)
                .isEqualTo(warehouse.getName())
                .path("warehouse.location")
                .entity(String.class)
                .isEqualTo(warehouse.getLocation());
    }

    @Test
    @WithMockUser
    void shouldReturnNullWhenWarehouseNotFoundTest() {
        Long id = 999L;
        when(warehouseRepository.findById(id)).thenReturn(Optional.empty());

        @Language("GraphQl")
        var query =
                """
            query($id: ID!) {
              warehouse(id: $id) {
                name
              }
            }
        """;

        graphQlTester
                .document(query)
                .variable("id", id)
                .execute()
                .path("warehouse")
                .valueIsNull();
    }

    @Test
    @WithMockUser
    void shouldReturnAllWarehousesTest() {
        when(warehouseRepository.findAll()).thenReturn(List.of(warehouse));

        @Language("GraphQl")
        var query =
                """
            query {
              warehouses {
                name
              }
            }
        """;

        graphQlTester
                .document(query)
                .execute()
                .path("warehouses[0].name")
                .entity(String.class)
                .isEqualTo(warehouse.getName());
    }

    @Test
    @WithMockUser
    void shouldReturnEmptyWarehousesListTest() {
        when(warehouseRepository.findAll()).thenReturn(List.of());

        @Language("GraphQl")
        var query =
                """
            query {
              warehouses {
                name
              }
            }
        """;

        graphQlTester
                .document(query)
                .execute()
                .path("warehouses")
                .entityList(Object.class)
                .hasSize(0);
    }

    @Test
    @WithMockUser
    void shouldReturnWarehouseInventoryTest() {
        Long id = warehouse.getId();
        when(warehouseRepository.findById(id)).thenReturn(Optional.of(warehouse));
        when(inventoryRepository.findAllByWarehouseId(id)).thenReturn(List.of(inventory));

        @Language("GraphQl")
        var query =
                """
            query($id: ID!) {
              warehouse(id: $id) {
                inventory {
                  quantity
                }
              }
            }
        """;

        graphQlTester
                .document(query)
                .variable("id", id)
                .execute()
                .path("warehouse.inventory[0].quantity")
                .entity(Integer.class)
                .isEqualTo(inventory.getQuantity());
    }

    @Test
    @WithMockUser
    void shouldReturnProductVariantInventoryTest() {
        Long productId = product.getId();
        Long variantId = productVariant.getId();
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(productVariantRepository.findAllByProductId(productId)).thenReturn(List.of(productVariant));
        when(inventoryRepository.findAllByProductVariantId(variantId)).thenReturn(List.of(inventory));

        @Language("GraphQl")
        var query =
                """
            query($productId: ID!) {
              product(id: $productId) {
                variants {
                  inventory {
                    quantity
                  }
                }
              }
            }
        """;

        graphQlTester
                .document(query)
                .variable("productId", productId)
                .execute()
                .path("product.variants[0].inventory[0].quantity")
                .entity(Integer.class)
                .isEqualTo(inventory.getQuantity());
    }

    @Test
    @WithMockUser
    void shouldCreateWarehouseTest() {
        when(warehouseRepository.save(any(Warehouse.class))).thenReturn(warehouse);

        @Language("GraphQl")
        var mutation =
                """
            mutation {
              createWarehouse(input: { name: "Main Warehouse", location: "Tallinn" }) {
                name
                location
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .execute()
                .path("createWarehouse.name")
                .entity(String.class)
                .isEqualTo(warehouse.getName());
    }

    @Test
    @WithMockUser
    void shouldUpdateWarehouseTest() {
        Long id = warehouse.getId();
        when(warehouseRepository.findById(id)).thenReturn(Optional.of(warehouse));
        when(warehouseRepository.save(warehouse)).thenReturn(warehouse);

        @Language("GraphQl")
        var mutation =
                """
            mutation($id: ID!) {
              updateWarehouse(warehouseId: $id, input: { location: "Tartu" }) {
                location
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("id", id)
                .execute()
                .path("updateWarehouse.location")
                .entity(String.class)
                .isEqualTo("Tartu");
    }

    @Test
    @WithMockUser
    void shouldDeleteWarehouseTest() {
        Long id = warehouse.getId();
        when(warehouseRepository.findById(id)).thenReturn(Optional.of(warehouse));

        @Language("GraphQl")
        var mutation =
                """
            mutation($id: ID!) {
              deleteWarehouse(warehouseId: $id)
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("id", id)
                .execute()
                .path("deleteWarehouse")
                .entity(Boolean.class)
                .isEqualTo(true);
    }

    @Test
    @WithMockUser
    void shouldReturnFalseWhenDeletingMissingWarehouseTest() {
        Long id = 999L;
        when(warehouseRepository.findById(id)).thenReturn(Optional.empty());

        @Language("GraphQl")
        var mutation =
                """
            mutation($id: ID!) {
              deleteWarehouse(warehouseId: $id)
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("id", id)
                .execute()
                .path("deleteWarehouse")
                .entity(Boolean.class)
                .isEqualTo(false);
    }

    @Test
    @WithMockUser
    void shouldSetInventoryForNewProductVariantWarehousePairTest() {
        when(productVariantRepository.findById(productVariant.getId())).thenReturn(Optional.of(productVariant));
        when(warehouseRepository.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(inventoryRepository.findByProductVariantIdAndWarehouseId(productVariant.getId(), warehouse.getId()))
                .thenReturn(Optional.empty());
        when(inventoryRepository.save(any(Inventory.class))).thenReturn(inventory);

        @Language("GraphQl")
        var mutation =
                """
            mutation($variantId: ID!, $warehouseId: ID!) {
              setInventory(input: { productVariantId: $variantId, warehouseId: $warehouseId, quantity: 10 }) {
                quantity
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("variantId", productVariant.getId())
                .variable("warehouseId", warehouse.getId())
                .execute()
                .path("setInventory.quantity")
                .entity(Integer.class)
                .isEqualTo(10);
    }

    @Test
    @WithMockUser
    void shouldSetInventoryForExistingProductVariantWarehousePairTest() {
        when(productVariantRepository.findById(productVariant.getId())).thenReturn(Optional.of(productVariant));
        when(warehouseRepository.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(inventoryRepository.findByProductVariantIdAndWarehouseId(productVariant.getId(), warehouse.getId()))
                .thenReturn(Optional.of(inventory));
        when(inventoryRepository.save(inventory)).thenReturn(inventory);

        @Language("GraphQl")
        var mutation =
                """
            mutation($variantId: ID!, $warehouseId: ID!) {
              setInventory(input: { productVariantId: $variantId, warehouseId: $warehouseId, quantity: 25 }) {
                quantity
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("variantId", productVariant.getId())
                .variable("warehouseId", warehouse.getId())
                .execute()
                .path("setInventory.quantity")
                .entity(Integer.class)
                .isEqualTo(25);
    }
}
