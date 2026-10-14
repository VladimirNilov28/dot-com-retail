package ee.bytecore.backend.services.catalog;

/** A category facet entry: how many matching products belong to this category. */
public record CategoryFacetCount(Long categoryId, String name, long count) {}
