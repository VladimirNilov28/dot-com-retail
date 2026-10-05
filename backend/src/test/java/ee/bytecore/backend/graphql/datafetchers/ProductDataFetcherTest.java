package ee.bytecore.backend.graphql.datafetchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@SpringBootTest(
        classes = {
            ProductQuery.class,
            ProductMutation.class,
            GraphQLConfig.class,
            ee.bytecore.backend.graphql.GraphQlExceptionResolver.class,
            LocalDateScalar.class,
            InstantScalar.class,
            ee.bytecore.backend.services.ProductService.class,
            ee.bytecore.backend.services.ProductVariantService.class,
            ee.bytecore.backend.graphql.dataloaders.ProductVariantsByProductIdDataLoader.class,
            ee.bytecore.backend.graphql.dataloaders.CategoriesByProductIdDataLoader.class
        })
@EnableDgsMockMvcTest
@AutoConfigureHttpGraphQlTester
@Tag("graphql")
class ProductDataFetcherTest {

    @MockitoBean
    ProductRepository productRepository;

    @MockitoBean
    ProductVariantRepository productVariantRepository;

    @MockitoBean
    ee.bytecore.backend.repositories.category.CategoryRepository categoryRepository;

    private Product product;
    private ProductVariant productVariant;

    @Autowired
    private GraphQlTester graphQlTester;

    @BeforeEach
    void setUp() {
        product = Product.create("T-Shirt", "t-shirt", "A plain t-shirt");
        product.setId(1L);
        productVariant = ProductVariant.create(product, "TSHIRT-M-BLACK", new BigDecimal("19.99"));
        productVariant.setId(1L);
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
        when(productVariantRepository.findAllByProductIdIn(List.of(id))).thenReturn(List.of(productVariant));

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
    void shouldResolveProductForVariantTest() {
        Long id = product.getId();
        when(productRepository.findById(id)).thenReturn(Optional.of(product));
        when(productVariantRepository.findAllByProductIdIn(List.of(id))).thenReturn(List.of(productVariant));
        when(productVariantRepository.findById(productVariant.getId())).thenReturn(Optional.of(productVariant));

        @Language("GraphQl")
        var query =
                """
            query($id: ID!) {
              product(id: $id) {
                variants {
                  product {
                    name
                  }
                }
              }
            }
        """;

        graphQlTester
                .document(query)
                .variable("id", id)
                .execute()
                .path("product.variants[0].product.name")
                .entity(String.class)
                .isEqualTo(product.getName());
    }

    @Test
    @WithMockUser
    void shouldReturnEmptyProductVariantsTest() {
        Long id = product.getId();
        when(productRepository.findById(id)).thenReturn(Optional.of(product));
        when(productVariantRepository.findAllByProductIdIn(List.of(id))).thenReturn(List.of());

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
    void shouldRejectCreateProductWithNonExistentCategoryIdTest() {
        when(categoryRepository.findAllById(List.of(999L))).thenReturn(List.of());
        when(productRepository.save(any(Product.class))).thenReturn(product);

        @Language("GraphQl")
        var mutation =
                """
            mutation {
              createProduct(input: { name: "T-Shirt", slug: "t-shirt", categoryIds: ["999"] }) {
                name
              }
            }
        """;

        graphQlTester.document(mutation).execute().errors().satisfy(errors -> assertThat(errors)
                .as("a categoryId that doesn't reference an existing category must be rejected")
                .isNotEmpty());
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

    @ParameterizedTest
    @ValueSource(strings = {"899.99", "\"899.99\""})
    @WithMockUser
    void shouldBindVariantPriceWithoutAttributesTest(String price) {
        stubVariantCreation();
        graphQlTester
                .document(
                        """
                        mutation {
                          createProductVariant(input: {productId: "1", sku: "BOUND", price: %s}) {
                            price
                            attributes
                          }
                        }
                        """
                                .formatted(price))
                .execute()
                .path("createProductVariant.price")
                .entity(BigDecimal.class)
                .isEqualTo(new BigDecimal("899.99"))
                .path("createProductVariant.attributes")
                .entity(Map.class)
                .isEqualTo(Map.of());
    }

    @Test
    @WithMockUser
    void shouldRoundTripLiteralJsonAttributesTest() {
        stubVariantCreation();
        graphQlTester
                .document(
                        """
                        mutation {
                          createProductVariant(input: {
                            productId: "1", sku: "JSON", price: 12.50,
                            attributes: {
                              color: "blue", dimensions: {length: 12},
                              tags: ["new", "small"], enabled: true
                            }
                          }) {
                            attributes
                          }
                        }
                        """)
                .execute()
                .path("createProductVariant.attributes.color")
                .entity(String.class)
                .isEqualTo("blue")
                .path("createProductVariant.attributes.dimensions.length")
                .entity(Integer.class)
                .isEqualTo(12)
                .path("createProductVariant.attributes.tags")
                .entityList(String.class)
                .containsExactly("new", "small")
                .path("createProductVariant.attributes.enabled")
                .entity(Boolean.class)
                .isEqualTo(true);
    }

    @Test
    @WithMockUser
    void shouldRoundTripVariableJsonAttributesTest() {
        stubVariantCreation();
        Map<String, Object> attributes = Map.of("color", "green", "tags", List.of("sale"));
        graphQlTester
                .document(
                        """
                        mutation($input: CreateProductVariantInput!) {
                          createProductVariant(input: $input) { attributes }
                        }
                        """)
                .variable(
                        "input",
                        Map.of("productId", "1", "sku", "VARIABLE", "price", "12.50", "attributes", attributes))
                .execute()
                .path("createProductVariant.attributes")
                .entity(Map.class)
                .isEqualTo(attributes);
    }

    @Test
    @WithMockUser
    void shouldUpdateJsonAttributesTest() {
        when(productVariantRepository.findById(productVariant.getId())).thenReturn(Optional.of(productVariant));
        when(productVariantRepository.save(productVariant)).thenReturn(productVariant);
        graphQlTester
                .document(
                        """
                        mutation($input: UpdateProductVariantInput!) {
                          updateProductVariant(variantId: "1", input: $input) { attributes }
                        }
                        """)
                .variable("input", Map.of("attributes", Map.of("nested", Map.of("sizes", List.of("M", "L")))))
                .execute()
                .path("updateProductVariant.attributes.nested.sizes")
                .entityList(String.class)
                .containsExactly("M", "L");
    }

    @Test
    @WithMockUser
    void shouldReadJsonAttributesWithoutJacksonMetadataTest() {
        productVariant.setAttributes(Map.of("color", "red"));
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(productVariantRepository.findAllByProductIdIn(List.of(product.getId())))
                .thenReturn(List.of(productVariant));
        graphQlTester
                .document("{ product(id: \"1\") { variants { attributes } } }")
                .execute()
                .path("product.variants[0].attributes")
                .entity(Map.class)
                .isEqualTo(Map.of("color", "red"));
    }

    @Test
    @WithMockUser
    void shouldRejectNonObjectAttributesWithValidationErrorTest() {
        graphQlTester
                .document(
                        """
                        mutation {
                          createProductVariant(input: {productId: "1", sku: "BAD", price: 12, attributes: ["bad"]}) {
                            id
                          }
                        }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors).anySatisfy(error -> assertThat(error.getMessage())
                        .contains("Attributes must be a JSON object")));
    }

    private void stubVariantCreation() {
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(productVariantRepository.save(any(ProductVariant.class))).thenAnswer(invocation -> {
            ProductVariant saved = invocation.getArgument(0);
            saved.setId(productVariant.getId());
            return saved;
        });
    }

    @Test
    @WithMockUser
    void shouldRejectCreateProductVariantWithNegativePriceTest() {
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(productVariantRepository.save(any(ProductVariant.class))).thenReturn(productVariant);

        @Language("GraphQl")
        var mutation =
                """
            mutation($productId: ID!) {
              createProductVariant(input: { productId: $productId, sku: "BAD-SKU", price: "-5.00" }) {
                sku
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("productId", product.getId())
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .as("a negative price must be rejected")
                        .isNotEmpty());
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
