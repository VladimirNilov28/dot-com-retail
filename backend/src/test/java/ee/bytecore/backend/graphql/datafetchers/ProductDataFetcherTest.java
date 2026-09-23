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

import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.graphql.datafetchers.product.ProductMutation;
import ee.bytecore.backend.graphql.datafetchers.product.ProductQuery;
import ee.bytecore.backend.graphql.scalars.GraphQLConfig;
import ee.bytecore.backend.graphql.scalars.InstantScalar;
import ee.bytecore.backend.graphql.scalars.LocalDateScalar;
import ee.bytecore.backend.repositories.product.ProductRepository;
import ee.bytecore.backend.repositories.product.ProductVariantRepository;

import com.netflix.graphql.dgs.test.EnableDgsMockMvcTest;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@SpringBootTest(classes = {ProductQuery.class, ProductMutation.class, GraphQLConfig.class, LocalDateScalar.class, InstantScalar.class})
@EnableDgsMockMvcTest
@AutoConfigureHttpGraphQlTester
@Tag("graphql")
class ProductDataFetcherTest {

    @MockitoBean
    ProductRepository productRepository;

    @MockitoBean
    ProductVariantRepository productVariantRepository;

    private Product product;
    private ProductVariant productVariant;

    @Autowired
    private GraphQlTester graphQlTester;

    @BeforeEach
    void setUp() {
        product = Product.create("T-Shirt", "t-shirt", "A plain t-shirt");
        productVariant = ProductVariant.create(product, "TSHIRT-M-BLACK", new BigDecimal("19.99"));
    }

    @Test
    @WithMockUser
    void shouldReturnProductByIdTest() {
        Long id = product.getId();
        when(productRepository.findById(id)).thenReturn(Optional.of(product));

        @Language("GraphQl")
        var query =
                """
            query($id: ID!) {
              product(id: $id) {
                name
                slug
                description
              }
            }
        """;

        graphQlTester
                .document(query)
                .variable("id", id)
                .execute()
                .path("product.name")
                .entity(String.class)
                .isEqualTo(product.getName())
                .path("product.slug")
                .entity(String.class)
                .isEqualTo(product.getSlug())
                .path("product.description")
                .entity(String.class)
                .isEqualTo(product.getDescription());
    }

    @Test
    @WithMockUser
    void shouldReturnProductBySlugTest() {
        when(productRepository.findBySlug("t-shirt")).thenReturn(Optional.of(product));

        @Language("GraphQl")
        var query =
                """
            query($slug: String!) {
              product(slug: $slug) {
                name
              }
            }
        """;

        graphQlTester
                .document(query)
                .variable("slug", "t-shirt")
                .execute()
                .path("product.name")
                .entity(String.class)
                .isEqualTo(product.getName());
    }

    @Test
    @WithMockUser
    void shouldReturnNullWhenProductNotFoundBySlugTest() {
        when(productRepository.findBySlug("missing")).thenReturn(Optional.empty());

        @Language("GraphQl")
        var query =
                """
            query($slug: String!) {
              product(slug: $slug) {
                name
              }
            }
        """;

        graphQlTester
                .document(query)
                .variable("slug", "missing")
                .execute()
                .path("product")
                .valueIsNull();
    }

    @Test
    @WithMockUser
    void shouldReturnAllProductsTest() {
        when(productRepository.findAll()).thenReturn(List.of(product));

        @Language("GraphQl")
        var query =
                """
            query {
              products {
                name
                slug
              }
            }
        """;

        graphQlTester
                .document(query)
                .execute()
                .path("products[0].name")
                .entity(String.class)
                .isEqualTo(product.getName());
    }

    @Test
    @WithMockUser
    void shouldReturnEmptyProductsListTest() {
        when(productRepository.findAll()).thenReturn(List.of());

        @Language("GraphQl")
        var query =
                """
            query {
              products {
                name
              }
            }
        """;

        graphQlTester
                .document(query)
                .execute()
                .path("products")
                .entityList(Object.class)
                .hasSize(0);
    }

    @Test
    @WithMockUser
    void shouldReturnProductVariantsTest() {
        Long id = product.getId();
        when(productRepository.findById(id)).thenReturn(Optional.of(product));
        when(productVariantRepository.findAllByProductId(id)).thenReturn(List.of(productVariant));

        @Language("GraphQl")
        var query =
                """
            query($id: ID!) {
              product(id: $id) {
                variants {
                  sku
                  price
                }
              }
            }
        """;

        graphQlTester
                .document(query)
                .variable("id", id)
                .execute()
                .path("product.variants[0].sku")
                .entity(String.class)
                .isEqualTo(productVariant.getSku())
                .path("product.variants[0].price")
                .entity(BigDecimal.class)
                .isEqualTo(productVariant.getPrice());
    }

    @Test
    @WithMockUser
    void shouldReturnEmptyProductVariantsTest() {
        Long id = product.getId();
        when(productRepository.findById(id)).thenReturn(Optional.of(product));
        when(productVariantRepository.findAllByProductId(id)).thenReturn(List.of());

        @Language("GraphQl")
        var query =
                """
            query($id: ID!) {
              product(id: $id) {
                variants {
                  sku
                }
              }
            }
        """;

        graphQlTester
                .document(query)
                .variable("id", id)
                .execute()
                .path("product.variants")
                .entityList(Object.class)
                .hasSize(0);
    }

    @Test
    @WithMockUser
    void shouldCreateProductTest() {
        when(productRepository.save(any(Product.class))).thenReturn(product);

        @Language("GraphQl")
        var mutation =
                """
            mutation {
              createProduct(input: { name: "T-Shirt", slug: "t-shirt" }) {
                name
                slug
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .execute()
                .path("createProduct.name")
                .entity(String.class)
                .isEqualTo(product.getName());
    }

    @Test
    @WithMockUser
    void shouldUpdateProductTest() {
        Long id = product.getId();
        when(productRepository.findById(id)).thenReturn(Optional.of(product));
        when(productRepository.save(product)).thenReturn(product);

        @Language("GraphQl")
        var mutation =
                """
            mutation($id: ID!) {
              updateProduct(productId: $id, input: { name: "Long Sleeve T-Shirt" }) {
                name
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("id", id)
                .execute()
                .path("updateProduct.name")
                .entity(String.class)
                .isEqualTo("Long Sleeve T-Shirt");
    }

    @Test
    @WithMockUser
    void shouldDeleteProductTest() {
        Long id = product.getId();
        when(productRepository.findById(id)).thenReturn(Optional.of(product));

        @Language("GraphQl")
        var mutation =
                """
            mutation($id: ID!) {
              deleteProduct(productId: $id)
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("id", id)
                .execute()
                .path("deleteProduct")
                .entity(Boolean.class)
                .isEqualTo(true);
    }

    @Test
    @WithMockUser
    void shouldReturnFalseWhenDeletingMissingProductTest() {
        Long id = 999L;
        when(productRepository.findById(id)).thenReturn(Optional.empty());

        @Language("GraphQl")
        var mutation =
                """
            mutation($id: ID!) {
              deleteProduct(productId: $id)
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("id", id)
                .execute()
                .path("deleteProduct")
                .entity(Boolean.class)
                .isEqualTo(false);
    }

    @Test
    @WithMockUser
    void shouldCreateProductVariantTest() {
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(productVariantRepository.save(any(ProductVariant.class))).thenReturn(productVariant);

        @Language("GraphQl")
        var mutation =
                """
            mutation($productId: ID!) {
              createProductVariant(input: { productId: $productId, sku: "TSHIRT-M-BLACK", price: "19.99" }) {
                sku
                price
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("productId", product.getId())
                .execute()
                .path("createProductVariant.sku")
                .entity(String.class)
                .isEqualTo(productVariant.getSku());
    }

    @Test
    @WithMockUser
    void shouldUpdateProductVariantTest() {
        Long id = productVariant.getId();
        when(productVariantRepository.findById(id)).thenReturn(Optional.of(productVariant));
        when(productVariantRepository.save(productVariant)).thenReturn(productVariant);

        @Language("GraphQl")
        var mutation =
                """
            mutation($id: ID!) {
              updateProductVariant(variantId: $id, input: { isActive: false }) {
                isActive
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("id", id)
                .execute()
                .path("updateProductVariant.isActive")
                .entity(Boolean.class)
                .isEqualTo(false);
    }

    @Test
    @WithMockUser
    void shouldDeleteProductVariantTest() {
        Long id = productVariant.getId();
        when(productVariantRepository.findById(id)).thenReturn(Optional.of(productVariant));

        @Language("GraphQl")
        var mutation =
                """
            mutation($id: ID!) {
              deleteProductVariant(variantId: $id)
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("id", id)
                .execute()
                .path("deleteProductVariant")
                .entity(Boolean.class)
                .isEqualTo(true);
    }

    @Test
    @WithMockUser
    void shouldReturnFalseWhenDeletingMissingProductVariantTest() {
        Long id = 999L;
        when(productVariantRepository.findById(id)).thenReturn(Optional.empty());

        @Language("GraphQl")
        var mutation =
                """
            mutation($id: ID!) {
              deleteProductVariant(variantId: $id)
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("id", id)
                .execute()
                .path("deleteProductVariant")
                .entity(Boolean.class)
                .isEqualTo(false);
    }
}
