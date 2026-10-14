package ee.bytecore.backend.services.catalog;

/** Supported database-backed sort orders for catalog search. */
public enum ProductSortOption {
    RELEVANCE,
    PRICE_ASC,
    PRICE_DESC,
    RATING_DESC
}
