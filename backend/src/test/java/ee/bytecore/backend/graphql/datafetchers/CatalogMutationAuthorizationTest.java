package ee.bytecore.backend.graphql.datafetchers;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import ee.bytecore.backend.entities.category.Category;
import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.graphql.datafetchers.category.CategoryMutation;
import ee.bytecore.backend.graphql.datafetchers.category.CategoryQuery;
import ee.bytecore.backend.graphql.datafetchers.product.ProductMutation;
import ee.bytecore.backend.graphql.datafetchers.product.ProductQuery;
import ee.bytecore.backend.graphql.scalars.GraphQLConfig;
import ee.bytecore.backend.graphql.scalars.InstantScalar;
import ee.bytecore.backend.graphql.scalars.LocalDateScalar;
import ee.bytecore.backend.repositories.category.CategoryRepository;
import ee.bytecore.backend.repositories.product.ProductRepository;
import ee.bytecore.backend.repositories.product.ProductVariantRepository;
import ee.bytecore.backend.services.CategoryService;
import ee.bytecore.backend.services.ProductService;
import ee.bytecore.backend.services.ProductVariantService;

import com.netflix.graphql.dgs.test.EnableDgsMockMvcTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves createCategory/deleteCategory/deleteProduct actually enforce
 * @PreAuthorize("hasAnyRole('CATALOG_MANAGER','ADMIN')") against the real
 * SecurityFilterChain + JwtAuthenticationConverter (not @WithMockUser, which
 * doesn't survive the real OAuth2 resource server filter chain imported
 * here — see MeQueryAuthenticationTest/UserMutationAuthorizationTest for the
 * same pattern established in the User domain).
 */
@SpringBootTest(
        classes = {
            CategoryQuery.class,
            CategoryMutation.class,
            ProductQuery.class,
            ProductMutation.class,
            GraphQLConfig.class,
            LocalDateScalar.class,
            InstantScalar.class,
            SecurityConfig.class,
            CategoryService.class,
            ProductService.class,
            ProductVariantService.class,
            ee.bytecore.backend.graphql.dataloaders.ProductVariantsByProductIdDataLoader.class,
            ee.bytecore.backend.graphql.dataloaders.CategoriesByProductIdDataLoader.class
        })
@EnableDgsMockMvcTest
class CatalogMutationAuthorizationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    CategoryRepository categoryRepository;

    @MockitoBean
    ProductRepository productRepository;

    @MockitoBean
    ProductVariantRepository productVariantRepository;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private Category category;
    private Product product;

    @BeforeEach
    void setUp() {
        category = Category.create("Shoes", "shoes", null);
        category.setId(1L);
        product = Product.create("T-Shirt", "t-shirt", "A plain t-shirt");
        product.setId(1L);
    }

    @Test
    void shouldRejectDeleteCategoryForNonCatalogManagerTest() throws Exception {
        mockMvc.perform(post("/graphql")
                        .with(asAuthenticatedJwt("2", "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"mutation { deleteCategory(categoryId: \\\"1\\\") }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldAllowDeleteCategoryForCatalogManagerTest() throws Exception {
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));

        mockMvc.perform(post("/graphql")
                        .with(asAuthenticatedJwt("2", "CATALOG_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"mutation { deleteCategory(categoryId: \\\"1\\\") }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleteCategory").value(true));
    }

    @Test
    void shouldRejectDeleteProductForNonCatalogManagerTest() throws Exception {
        mockMvc.perform(post("/graphql")
                        .with(asAuthenticatedJwt("2", "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"mutation { deleteProduct(productId: \\\"1\\\") }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldAllowDeleteProductForCatalogManagerTest() throws Exception {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        mockMvc.perform(post("/graphql")
                        .with(asAuthenticatedJwt("2", "CATALOG_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"mutation { deleteProduct(productId: \\\"1\\\") }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleteProduct").value(true));
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
