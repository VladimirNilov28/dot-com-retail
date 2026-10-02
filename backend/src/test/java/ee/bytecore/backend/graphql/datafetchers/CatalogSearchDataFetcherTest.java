package ee.bytecore.backend.graphql.datafetchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.graphql.datafetchers.product.CatalogSearchQuery;
import ee.bytecore.backend.graphql.scalars.GraphQLConfig;
import ee.bytecore.backend.graphql.scalars.InstantScalar;
import ee.bytecore.backend.graphql.scalars.LocalDateScalar;
import ee.bytecore.backend.repositories.category.CategoryRepository;
import ee.bytecore.backend.repositories.product.CatalogSearchRepository;
import ee.bytecore.backend.repositories.product.ProductRepository;
import ee.bytecore.backend.services.catalog.AttributeFacetGroup;
import ee.bytecore.backend.services.catalog.AttributeValueCount;
import ee.bytecore.backend.services.catalog.CategoryFacetCount;
import ee.bytecore.backend.services.catalog.PriceRange;
import ee.bytecore.backend.services.catalog.ProductSuggestion;

import com.netflix.graphql.dgs.test.EnableDgsMockMvcTest;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

@SpringBootTest(
        classes = {
            CatalogSearchQuery.class,
            GraphQLConfig.class,
            ee.bytecore.backend.graphql.GraphQlExceptionResolver.class,
            LocalDateScalar.class,
            InstantScalar.class,
            ee.bytecore.backend.services.CatalogSearchService.class,
        })
@EnableDgsMockMvcTest
@AutoConfigureHttpGraphQlTester
class CatalogSearchDataFetcherTest {

    @MockitoBean
    CatalogSearchRepository catalogSearchRepository;

    @MockitoBean
    ProductRepository productRepository;

    @MockitoBean
    CategoryRepository categoryRepository;

    @Autowired
    private GraphQlTester graphQlTester;

    @Test
    @WithMockUser
    void shouldReturnSearchResultsWithFacetsAndPageInfoTest() {
        Product product = Product.create("T-Shirt", "t-shirt", "A plain t-shirt");
        product.setId(1L);

        when(catalogSearchRepository.searchProductIds(any()))
                .thenReturn(new CatalogSearchRepository.SearchIds(List.of(1L), 1));
        when(productRepository.findAllByIdInWithCategories(List.of(1L))).thenReturn(List.of(product));
        when(catalogSearchRepository.categoryFacets(any()))
                .thenReturn(List.of(new CategoryFacetCount(2L, "Apparel", 1)));
        when(catalogSearchRepository.attributeFacets(any()))
                .thenReturn(List.of(new AttributeFacetGroup("color", List.of(new AttributeValueCount("black", 1)))));
        when(catalogSearchRepository.priceFacet(any()))
                .thenReturn(new PriceRange(new BigDecimal("19.99"), new BigDecimal("19.99")));

        @Language("GraphQL")
        String query =
                """
                query {
                  searchProducts(input: { query: "T-Shirt" }) {
                    items { id name }
                    pageInfo { page size totalItems totalPages }
                    facets {
                      categories { id name count }
                      attributes { name values { value count } }
                      price { min max }
                    }
                  }
                }
                """;

        graphQlTester
                .document(query)
                .execute()
                .path("searchProducts.items[0].name")
                .entity(String.class)
                .isEqualTo("T-Shirt")
                .path("searchProducts.pageInfo.totalItems")
                .entity(Integer.class)
                .isEqualTo(1)
                .path("searchProducts.facets.categories[0].name")
                .entity(String.class)
                .isEqualTo("Apparel")
                .path("searchProducts.facets.attributes[0].values[0].value")
                .entity(String.class)
                .isEqualTo("black")
                .path("searchProducts.facets.price.min")
                .entity(String.class)
                .isEqualTo("19.99");
    }

    @Test
    @WithMockUser
    void shouldReturnBadRequestForInvalidPriceRangeTest() {
        @Language("GraphQL")
        String query =
                """
                query {
                  searchProducts(input: { filters: { minPrice: 50, maxPrice: 10 } }) {
                    items { id }
                  }
                }
                """;

        graphQlTester.document(query).execute().errors().satisfy(errors -> assertThat(errors)
                .as("minPrice greater than maxPrice must be rejected")
                .isNotEmpty());
    }

    @Test
    @WithMockUser
    void shouldReturnSuggestionsTest() {
        when(catalogSearchRepository.suggest("iph", 10))
                .thenReturn(List.of(new ProductSuggestion(1L, "IPhone Case", "iphone-case")));

        @Language("GraphQL")
        String query =
                """
                query {
                  productSearchSuggestions(query: "iph") {
                    productId
                    name
                    slug
                  }
                }
                """;

        graphQlTester
                .document(query)
                .execute()
                .path("productSearchSuggestions[0].name")
                .entity(String.class)
                .isEqualTo("IPhone Case");
    }
}
