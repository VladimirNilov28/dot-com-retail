package ee.bytecore.backend.services;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.repositories.category.CategoryRepository;
import ee.bytecore.backend.repositories.product.CatalogSearchRepository;
import ee.bytecore.backend.repositories.product.CatalogSearchRepository.SearchIds;
import ee.bytecore.backend.repositories.product.ProductRepository;
import ee.bytecore.backend.services.catalog.CatalogFacets;
import ee.bytecore.backend.services.catalog.CatalogSearchCriteria;
import ee.bytecore.backend.services.catalog.CatalogSearchResult;
import ee.bytecore.backend.services.catalog.ProductSortOption;
import ee.bytecore.backend.services.catalog.ProductSuggestion;
import ee.bytecore.backend.services.catalog.SearchPageInfo;

/**
 * Catalog product discovery: search, filters/facets, sorting, pagination,
 * and search suggestions. Kept separate from {@link ProductService} (which
 * owns plain CRUD) since discovery has its own dedicated query/aggregation
 * concerns; this is the one focused service for that, not a general-purpose
 * search engine abstraction — PostgreSQL is the only implementation.
 */
@Service
public class CatalogSearchService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    static final int DEFAULT_SUGGESTION_LIMIT = 10;
    static final int MAX_SUGGESTION_LIMIT = 25;

    private final CatalogSearchRepository catalogSearchRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    public CatalogSearchService(
            CatalogSearchRepository catalogSearchRepository,
            ProductRepository productRepository,
            CategoryRepository categoryRepository) {
        this.catalogSearchRepository = catalogSearchRepository;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
    }

    public CatalogSearchResult search(CatalogSearchCriteria rawCriteria) {
        CatalogSearchCriteria criteria = validate(rawCriteria);

        SearchIds searchIds = catalogSearchRepository.searchProductIds(criteria);
        List<Product> products = hydrate(searchIds.productIds());

        int totalPages = criteria.size() == 0 ? 0 : (int) Math.ceil((double) searchIds.totalItems() / criteria.size());
        SearchPageInfo pageInfo =
                new SearchPageInfo(criteria.page(), criteria.size(), searchIds.totalItems(), totalPages);

        CatalogFacets facets = new CatalogFacets(
                catalogSearchRepository.categoryFacets(criteria),
                catalogSearchRepository.attributeFacets(criteria),
                catalogSearchRepository.priceFacet(criteria));

        return new CatalogSearchResult(products, pageInfo, facets);
    }

    public List<ProductSuggestion> suggest(String query, Integer limit) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Suggestion query must not be blank");
        }
        int effectiveLimit = limit == null ? DEFAULT_SUGGESTION_LIMIT : limit;
        if (effectiveLimit <= 0) {
            throw new IllegalArgumentException("Suggestion limit must be positive");
        }
        effectiveLimit = Math.min(effectiveLimit, MAX_SUGGESTION_LIMIT);
        return catalogSearchRepository.suggest(query.trim(), effectiveLimit);
    }

    /**
     * Loads full Product entities (with categories eagerly joined via the
     * existing {@code findAllByIdInWithCategories} query, reused as-is) for
     * the matched ids, then re-applies the search-determined order — the
     * batch IN-query does not preserve it. Variants are resolved later via
     * the existing {@code variantsByProductId} DataLoader at the GraphQL
     * layer, so no extra query is issued here for them.
     */
    private List<Product> hydrate(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        List<Product> found = productRepository.findAllByIdInWithCategories(productIds);
        Map<Long, Product> byId = found.stream().collect(java.util.stream.Collectors.toMap(Product::getId, p -> p));
        return productIds.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(p -> productIds.indexOf(p.getId())))
                .toList();
    }

    private CatalogSearchCriteria validate(CatalogSearchCriteria criteria) {
        BigDecimal minPrice = criteria.minPrice();
        BigDecimal maxPrice = criteria.maxPrice();
        if (minPrice != null && minPrice.signum() < 0) {
            throw new IllegalArgumentException("minPrice must not be negative");
        }
        if (maxPrice != null && maxPrice.signum() < 0) {
            throw new IllegalArgumentException("maxPrice must not be negative");
        }
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new IllegalArgumentException("minPrice must not be greater than maxPrice");
        }
        if (criteria.categoryId() != null && !categoryRepository.existsById(criteria.categoryId())) {
            throw new IllegalArgumentException(String.format("Category with id %s not found", criteria.categoryId()));
        }
        if (criteria.page() < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }

        int size = criteria.size() <= 0 ? DEFAULT_PAGE_SIZE : criteria.size();
        if (size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(String.format("size must not exceed %d", MAX_PAGE_SIZE));
        }

        ProductSortOption sort = criteria.sort() == null ? ProductSortOption.RELEVANCE : criteria.sort();

        return new CatalogSearchCriteria(
                criteria.query(),
                criteria.categoryId(),
                minPrice,
                maxPrice,
                criteria.attributes(),
                criteria.inStock(),
                sort,
                criteria.page(),
                size);
    }
}
