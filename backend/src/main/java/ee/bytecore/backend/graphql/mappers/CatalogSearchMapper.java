package ee.bytecore.backend.graphql.mappers;

import java.util.List;

import ee.bytecore.backend.services.catalog.AttributeFacetGroup;
import ee.bytecore.backend.services.catalog.AttributeFilter;
import ee.bytecore.backend.services.catalog.AttributeValueCount;
import ee.bytecore.backend.services.catalog.CatalogFacets;
import ee.bytecore.backend.services.catalog.CatalogSearchCriteria;
import ee.bytecore.backend.services.catalog.CatalogSearchResult;
import ee.bytecore.backend.services.catalog.CategoryFacetCount;
import ee.bytecore.backend.services.catalog.ProductSortOption;
import ee.bytecore.backend.services.catalog.ProductSuggestion;

import com.netflix.dgs.codegen.generated.types.AttributeValueFacet;
import com.netflix.dgs.codegen.generated.types.CategoryFacet;
import com.netflix.dgs.codegen.generated.types.PriceFacet;
import com.netflix.dgs.codegen.generated.types.ProductAttributeFacet;
import com.netflix.dgs.codegen.generated.types.ProductAttributeFilterInput;
import com.netflix.dgs.codegen.generated.types.ProductFacets;
import com.netflix.dgs.codegen.generated.types.ProductFilterInput;
import com.netflix.dgs.codegen.generated.types.ProductSearchInput;

public class CatalogSearchMapper {

    public static CatalogSearchCriteria toCriteria(ProductSearchInput input) {
        ProductFilterInput filters = input.getFilters();
        Long categoryId = null;
        java.math.BigDecimal minPrice = null;
        java.math.BigDecimal maxPrice = null;
        List<AttributeFilter> attributes = null;
        Boolean inStock = null;

        if (filters != null) {
            if (filters.getCategoryId() != null) {
                categoryId = Long.valueOf(filters.getCategoryId());
            }
            minPrice = filters.getMinPrice();
            maxPrice = filters.getMaxPrice();
            inStock = filters.getInStock();
            if (filters.getAttributes() != null) {
                attributes = filters.getAttributes().stream()
                        .map(CatalogSearchMapper::toAttributeFilter)
                        .toList();
            }
        }

        ProductSortOption sort = input.getSort() == null
                ? ProductSortOption.RELEVANCE
                : ProductSortOption.valueOf(input.getSort().name());
        int page = input.getPage() == null ? 0 : input.getPage();
        int size = input.getSize() == null ? 0 : input.getSize();

        return new CatalogSearchCriteria(
                input.getQuery(), categoryId, minPrice, maxPrice, attributes, inStock, sort, page, size);
    }

    public static com.netflix.dgs.codegen.generated.types.ProductSearchResult toGraphQlType(
            CatalogSearchResult result) {
        return com.netflix.dgs.codegen.generated.types.ProductSearchResult.newBuilder()
                .items(result.items().stream().map(ProductMapper::toGraphQlType).toList())
                .pageInfo(toGraphQlType(result.pageInfo()))
                .facets(toGraphQlType(result.facets()))
                .build();
    }

    public static com.netflix.dgs.codegen.generated.types.ProductSuggestion toGraphQlType(
            ProductSuggestion suggestion) {
        return com.netflix.dgs.codegen.generated.types.ProductSuggestion.newBuilder()
                .productId(suggestion.productId().toString())
                .name(suggestion.name())
                .slug(suggestion.slug())
                .build();
    }

    private static AttributeFilter toAttributeFilter(ProductAttributeFilterInput input) {
        return new AttributeFilter(input.getName(), input.getValue());
    }

    private static com.netflix.dgs.codegen.generated.types.SearchPageInfo toGraphQlType(
            ee.bytecore.backend.services.catalog.SearchPageInfo pageInfo) {
        return com.netflix.dgs.codegen.generated.types.SearchPageInfo.newBuilder()
                .page(pageInfo.page())
                .size(pageInfo.size())
                .totalItems((int) pageInfo.totalItems())
                .totalPages(pageInfo.totalPages())
                .build();
    }

    private static ProductFacets toGraphQlType(CatalogFacets facets) {
        return ProductFacets.newBuilder()
                .categories(facets.categories().stream()
                        .map(CatalogSearchMapper::toGraphQlType)
                        .toList())
                .attributes(facets.attributes().stream()
                        .map(CatalogSearchMapper::toGraphQlType)
                        .toList())
                .price(toGraphQlType(facets.price()))
                .build();
    }

    private static CategoryFacet toGraphQlType(CategoryFacetCount facet) {
        return CategoryFacet.newBuilder()
                .id(facet.categoryId().toString())
                .name(facet.name())
                .count((int) facet.count())
                .build();
    }

    private static ProductAttributeFacet toGraphQlType(AttributeFacetGroup group) {
        return ProductAttributeFacet.newBuilder()
                .name(group.name())
                .values(group.values().stream()
                        .map(CatalogSearchMapper::toGraphQlType)
                        .toList())
                .build();
    }

    private static AttributeValueFacet toGraphQlType(AttributeValueCount value) {
        return AttributeValueFacet.newBuilder()
                .value(value.value())
                .count((int) value.count())
                .build();
    }

    private static PriceFacet toGraphQlType(ee.bytecore.backend.services.catalog.PriceRange price) {
        return PriceFacet.newBuilder().min(price.min()).max(price.max()).build();
    }
}
