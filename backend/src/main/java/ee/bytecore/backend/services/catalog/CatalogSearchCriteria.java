package ee.bytecore.backend.services.catalog;

import java.math.BigDecimal;
import java.util.List;

/**
 * Catalog search request, mirroring the GraphQL {@code ProductSearchInput} /
 * {@code ProductFilterInput}. {@code page} is 0-based.
 *
 * <p>Variant-level filters ({@code minPrice}, {@code maxPrice}, {@code
 * attributes}, {@code inStock}) are combined and must all be satisfied by a
 * single matching {@code ProductVariant} — a product is never matched by
 * combining constraints across different variants.
 */
public record CatalogSearchCriteria(
        String query,
        Long categoryId,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        List<AttributeFilter> attributes,
        Boolean inStock,
        ProductSortOption sort,
        int page,
        int size) {}
