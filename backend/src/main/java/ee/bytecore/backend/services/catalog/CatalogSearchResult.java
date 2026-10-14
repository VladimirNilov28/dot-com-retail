package ee.bytecore.backend.services.catalog;

import java.util.List;

import ee.bytecore.backend.entities.product.Product;

/** The full result of a catalog search: matching products (in requested order), pagination, and facets. */
public record CatalogSearchResult(List<Product> items, SearchPageInfo pageInfo, CatalogFacets facets) {}
