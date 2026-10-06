package ee.bytecore.backend.graphql.datafetchers.product;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;

import ee.bytecore.backend.graphql.mappers.CatalogSearchMapper;
import ee.bytecore.backend.services.CatalogSearchService;

import com.netflix.dgs.codegen.generated.types.ProductSearchInput;
import com.netflix.dgs.codegen.generated.types.ProductSearchResult;
import com.netflix.dgs.codegen.generated.types.ProductSuggestion;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;

/**
 * Catalog discovery entry points (search/filters/sort/facets/suggestions).
 * Kept thin: all query/ranking/facet logic lives in {@link CatalogSearchService}.
 */
@DgsComponent
public class CatalogSearchQuery {

    private final CatalogSearchService catalogSearchService;

    public CatalogSearchQuery(CatalogSearchService catalogSearchService) {
        this.catalogSearchService = catalogSearchService;
    }

    @DgsQuery
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_READ)")
    public ProductSearchResult searchProducts(@InputArgument ProductSearchInput input) {
        return CatalogSearchMapper.toGraphQlType(catalogSearchService.search(CatalogSearchMapper.toCriteria(input)));
    }

    @DgsQuery
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_READ)")
    public List<ProductSuggestion> productSearchSuggestions(@InputArgument String query, @InputArgument Integer limit) {
        return catalogSearchService.suggest(query, limit).stream()
                .map(CatalogSearchMapper::toGraphQlType)
                .toList();
    }
}
