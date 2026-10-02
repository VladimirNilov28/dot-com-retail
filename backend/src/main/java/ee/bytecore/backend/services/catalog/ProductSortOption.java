package ee.bytecore.backend.services.catalog;

/**
 * Supported sort orders for catalog search. RATING is intentionally absent —
 * no rating/review data source exists in the current domain model, so rating
 * sorting is deferred rather than fabricated.
 */
public enum ProductSortOption {
    RELEVANCE,
    PRICE_ASC,
    PRICE_DESC
}
