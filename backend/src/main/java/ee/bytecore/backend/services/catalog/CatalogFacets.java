package ee.bytecore.backend.services.catalog;

import java.util.List;

/**
 * Facets computed over the product population that matches the current search query and filters
 * (search + filters applied, no per-facet self-exclusion — documented MVP simplification).
 */
public record CatalogFacets(
        List<CategoryFacetCount> categories, List<AttributeFacetGroup> attributes, PriceRange price) {}
