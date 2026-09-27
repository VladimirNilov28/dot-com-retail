package ee.bytecore.backend.graphql.datafetchers;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import ee.bytecore.backend.config.SecurityConfig;
import ee.bytecore.backend.entities.inventory.Warehouse;
import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.graphql.datafetchers.inventory.InventoryMutation;
import ee.bytecore.backend.graphql.datafetchers.inventory.InventoryQuery;
import ee.bytecore.backend.graphql.scalars.GraphQLConfig;
import ee.bytecore.backend.graphql.scalars.InstantScalar;
import ee.bytecore.backend.graphql.scalars.LocalDateScalar;
import ee.bytecore.backend.repositories.inventory.InventoryRepository;
import ee.bytecore.backend.repositories.inventory.WarehouseRepository;
import ee.bytecore.backend.repositories.product.ProductRepository;
import ee.bytecore.backend.repositories.product.ProductVariantRepository;
import ee.bytecore.backend.services.InventoryService;
import ee.bytecore.backend.services.ProductService;
import ee.bytecore.backend.services.ProductVariantService;
import ee.bytecore.backend.services.WarehouseService;

import com.netflix.graphql.dgs.test.EnableDgsMockMvcTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves deleteWarehouse/setInventory enforce
 * @PreAuthorize("hasAnyRole('WAREHOUSE','ADMIN')") against the real
 * SecurityFilterChain (same pattern as CatalogMutationAuthorizationTest).
 */
@SpringBootTest(
        classes = {
            InventoryQuery.class,
            InventoryMutation.class,
            GraphQLConfig.class,
            LocalDateScalar.class,
            InstantScalar.class,
            SecurityConfig.class,
            WarehouseService.class,
            InventoryService.class,
            ee.bytecore.backend.graphql.datafetchers.product.ProductQuery.class,
            ee.bytecore.backend.graphql.datafetchers.product.ProductMutation.class,
            ProductService.class,
            ProductVariantService.class,
            ee.bytecore.backend.graphql.dataloaders.ProductVariantsByProductIdDataLoader.class,
            ee.bytecore.backend.graphql.dataloaders.CategoriesByProductIdDataLoader.class
        })
@EnableDgsMockMvcTest
class InventoryMutationAuthorizationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    WarehouseRepository warehouseRepository;

    @MockitoBean
    InventoryRepository inventoryRepository;

    @MockitoBean
    ProductVariantRepository productVariantRepository;

    @MockitoBean
    ProductRepository productRepository;

    @MockitoBean
    ee.bytecore.backend.repositories.category.CategoryRepository categoryRepository;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private Warehouse warehouse;
    private ProductVariant productVariant;

    @BeforeEach
    void setUp() {
        warehouse = Warehouse.create("Main Warehouse", "Tallinn");
        warehouse.setId(1L);
        Product product = Product.create("T-Shirt", "t-shirt", "A plain t-shirt");
        product.setId(1L);
        productVariant = ProductVariant.create(product, "TSHIRT-M-BLACK", new BigDecimal("19.99"));
        productVariant.setId(1L);
    }

    @Test
    void shouldRejectDeleteWarehouseForNonWarehouseStaffTest() throws Exception {
        mockMvc.perform(post("/graphql")
                        .with(asAuthenticatedJwt("2", "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"mutation { deleteWarehouse(warehouseId: \\\"1\\\") }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldAllowDeleteWarehouseForWarehouseStaffTest() throws Exception {
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(warehouse));

        mockMvc.perform(post("/graphql")
                        .with(asAuthenticatedJwt("2", "WAREHOUSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"mutation { deleteWarehouse(warehouseId: \\\"1\\\") }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleteWarehouse").value(true));
    }

    @Test
    void shouldRejectSetInventoryForNonWarehouseStaffTest() throws Exception {
        mockMvc.perform(
                        post("/graphql")
                                .with(asAuthenticatedJwt("2", "USER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"query\":\"mutation { setInventory(input: { productVariantId: \\\"1\\\", warehouseId: \\\"1\\\", quantity: 5 }) { quantity } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    private RequestPostProcessor asAuthenticatedJwt(String subject, String role) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .claim("role", role)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        return authentication(jwtAuthenticationConverter.convert(jwt));
    }
}
