package ee.bytecore.backend.services.catalog;

/** Pagination metadata for a {@link CatalogSearchResult}. {@code page} is 0-based. */
public record SearchPageInfo(int page, int size, long totalItems, int totalPages) {}
