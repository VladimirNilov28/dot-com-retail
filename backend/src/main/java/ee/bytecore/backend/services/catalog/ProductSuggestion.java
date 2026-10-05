package ee.bytecore.backend.services.catalog;

/** A single autocomplete suggestion for the catalog search box. */
public record ProductSuggestion(Long productId, String name, String slug) {}
