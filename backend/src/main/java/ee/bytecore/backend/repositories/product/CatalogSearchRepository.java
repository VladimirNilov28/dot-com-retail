package ee.bytecore.backend.repositories.product;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

import ee.bytecore.backend.services.catalog.AttributeFacetGroup;
import ee.bytecore.backend.services.catalog.AttributeFilter;
import ee.bytecore.backend.services.catalog.AttributeValueCount;
import ee.bytecore.backend.services.catalog.CatalogSearchCriteria;
import ee.bytecore.backend.services.catalog.CategoryFacetCount;
import ee.bytecore.backend.services.catalog.PriceRange;
import ee.bytecore.backend.services.catalog.ProductSuggestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

/**
 * Native-SQL-backed catalog search queries.
 *
 * <p>Native SQL (rather than JPQL/Criteria) is used deliberately here: the
 * "same variant must satisfy every variant-level filter" requirement and the
 * JSONB attribute containment ({@code @>}) / {@code jsonb_each_text} facet
 * expansion don't have a clean JPQL/Criteria equivalent, and forcing one
 * would make the query harder to read and verify than the equivalent SQL.
 *
 * <p>All predicates operate on ids/aggregates in the database — there is no
 * in-memory filtering of loaded entities.
 */
@Repository
public class CatalogSearchRepository {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * A product is only discoverable through search if it has at least one
     * active variant satisfying every requested variant-level constraint
     * (price range, attributes, stock). All constraints are checked against
     * the SAME variant row ({@code v}) so e.g. "color=black" and "size=M"
     * can never be satisfied by two different variants of the same product.
     */
    private static final String VARIANT_MATCH_CONDITIONS =
            """
            v.is_active = TRUE
              AND (CAST(:minPrice AS numeric) IS NULL OR v.price >= CAST(:minPrice AS numeric))
              AND (CAST(:maxPrice AS numeric) IS NULL OR v.price <= CAST(:maxPrice AS numeric))
              AND v.attributes @> CAST(:attributesJson AS jsonb)
              AND (CAST(:inStockFilter AS boolean) IS NULL OR
                   ((CAST(:inStockFilter AS boolean) = TRUE
                     AND (SELECT COALESCE(SUM(i.quantity), 0) FROM inventory i WHERE i.product_variant_id = v.id) > 0)
                    OR (CAST(:inStockFilter AS boolean) = FALSE
                        AND (SELECT COALESCE(SUM(i.quantity), 0) FROM inventory i WHERE i.product_variant_id = v.id) = 0)))
            """;

    private static final String EFFECTIVE_PRICE_SUBQUERY =
            """
            (SELECT MIN(v.price) FROM product_variants v
             WHERE v.product_id = p.id AND
            """
                    + VARIANT_MATCH_CONDITIONS
                    + ")";

    private static final String RELEVANCE_RANK_EXPRESSION =
            """
            CASE
              WHEN CAST(:query AS text) IS NULL THEN 5
              WHEN LOWER(p.name) = LOWER(CAST(:query AS text)) THEN 0
              WHEN p.name ILIKE CONCAT(CAST(:query AS text), '%') THEN 1
              WHEN p.name ILIKE CONCAT('%', CAST(:query AS text), '%') THEN 2
              WHEN p.description ILIKE CONCAT('%', CAST(:query AS text), '%') THEN 3
              WHEN EXISTS (SELECT 1 FROM product_variants v WHERE v.product_id = p.id AND v.is_active = TRUE
                           AND v.sku ILIKE CONCAT('%', CAST(:query AS text), '%')) THEN 4
              ELSE 5
            END
            """;

    /**
     * A product only needs a matching variant when the request actually
     * carries a variant-level filter (price/attributes/stock). Otherwise a
     * product with no variants yet (or none matching) must still be
     * discoverable by name/description/category — variant filters are opt-in,
     * not an implicit "must have a sellable variant" requirement.
     */
    private static final String NO_VARIANT_FILTER_REQUESTED =
            """
            (CAST(:minPrice AS numeric) IS NULL AND CAST(:maxPrice AS numeric) IS NULL
             AND CAST(:inStockFilter AS boolean) IS NULL AND CAST(:attributesJson AS text) = '{}')
            """;

    private static final String BASE_WHERE =
            """
            (CAST(:query AS text) IS NULL OR p.name ILIKE CONCAT('%', CAST(:query AS text), '%')
             OR p.description ILIKE CONCAT('%', CAST(:query AS text), '%')
             OR EXISTS (SELECT 1 FROM product_variants v WHERE v.product_id = p.id AND v.is_active = TRUE
                        AND v.sku ILIKE CONCAT('%', CAST(:query AS text), '%')))
            AND (CAST(:categoryId AS bigint) IS NULL OR EXISTS (SELECT 1 FROM product_categories pc
                                                 WHERE pc.product_id = p.id AND pc.category_id = :categoryId))
            AND (
            """
                    + NO_VARIANT_FILTER_REQUESTED
                    + """
            OR EXISTS (SELECT 1 FROM product_variants v WHERE v.product_id = p.id AND
            """
                    + VARIANT_MATCH_CONDITIONS
                    + "))";

    private final EntityManager entityManager;

    public CatalogSearchRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public record SearchIds(List<Long> productIds, long totalItems) {}

    @SuppressWarnings("unchecked")
    public SearchIds searchProductIds(CatalogSearchCriteria criteria) {
        String orderBy =
                switch (criteria.sort()) {
                    case RELEVANCE -> "ORDER BY " + RELEVANCE_RANK_EXPRESSION + " ASC, p.id ASC";
                    case PRICE_ASC -> "ORDER BY " + EFFECTIVE_PRICE_SUBQUERY + " ASC NULLS LAST, p.id ASC";
                    case PRICE_DESC -> "ORDER BY " + EFFECTIVE_PRICE_SUBQUERY + " DESC NULLS LAST, p.id ASC";
                    case RATING_DESC -> "ORDER BY (SELECT AVG(r.stars) FROM product_ratings r WHERE r.product_id = p.id)"
                            + " DESC NULLS LAST, p.id ASC";
                };

        String idsSql =
                "SELECT p.id FROM products p WHERE " + BASE_WHERE + " " + orderBy + " LIMIT :limit OFFSET :offset";
        String countSql = "SELECT COUNT(*) FROM products p WHERE " + BASE_WHERE;

        Query idsQuery = entityManager.createNativeQuery(idsSql);
        bindCriteria(idsQuery, criteria);
        idsQuery.setParameter("limit", criteria.size());
        idsQuery.setParameter("offset", (long) criteria.page() * criteria.size());
        List<Object> rawIds = idsQuery.getResultList();
        List<Long> productIds =
                rawIds.stream().map(id -> ((Number) id).longValue()).toList();

        Query countQuery = entityManager.createNativeQuery(countSql);
        bindCriteria(countQuery, criteria);
        long totalItems = ((Number) countQuery.getSingleResult()).longValue();

        return new SearchIds(productIds, totalItems);
    }

    @SuppressWarnings("unchecked")
    public List<CategoryFacetCount> categoryFacets(CatalogSearchCriteria criteria) {
        String sql =
                """
                SELECT c.id, c.name, COUNT(DISTINCT p.id) AS cnt
                FROM products p
                JOIN product_categories pc ON pc.product_id = p.id
                JOIN categories c ON c.id = pc.category_id
                WHERE
                """
                        + BASE_WHERE
                        + " GROUP BY c.id, c.name ORDER BY cnt DESC, c.name ASC";
        Query query = entityManager.createNativeQuery(sql);
        bindCriteria(query, criteria);
        List<Object[]> rows = query.getResultList();
        return rows.stream()
                .map(row -> new CategoryFacetCount(
                        ((Number) row[0]).longValue(), (String) row[1], ((Number) row[2]).longValue()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    public List<AttributeFacetGroup> attributeFacets(CatalogSearchCriteria criteria) {
        String sql =
                """
                SELECT kv.key, kv.value, COUNT(DISTINCT p.id) AS cnt
                FROM products p
                JOIN product_variants v ON v.product_id = p.id AND
                """
                        + VARIANT_MATCH_CONDITIONS
                        + """
                CROSS JOIN LATERAL jsonb_each_text(v.attributes) AS kv(key, value)
                WHERE
                """
                        + BASE_WHERE
                        + " GROUP BY kv.key, kv.value ORDER BY kv.key ASC, cnt DESC, kv.value ASC";
        Query query = entityManager.createNativeQuery(sql);
        bindCriteria(query, criteria);
        List<Object[]> rows = query.getResultList();

        Map<String, List<AttributeValueCount>> byName = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String name = (String) row[0];
            String value = (String) row[1];
            long count = ((Number) row[2]).longValue();
            byName.computeIfAbsent(name, k -> new java.util.ArrayList<>()).add(new AttributeValueCount(value, count));
        }
        return byName.entrySet().stream()
                .map(e -> new AttributeFacetGroup(e.getKey(), e.getValue()))
                .toList();
    }

    public PriceRange priceFacet(CatalogSearchCriteria criteria) {
        String sql = "SELECT MIN(ep), MAX(ep) FROM (SELECT " + EFFECTIVE_PRICE_SUBQUERY
                + " AS ep FROM products p WHERE " + BASE_WHERE + ") t";
        Query query = entityManager.createNativeQuery(sql);
        bindCriteria(query, criteria);
        Object[] row = (Object[]) query.getSingleResult();
        BigDecimal min = row[0] == null ? null : (BigDecimal) row[0];
        BigDecimal max = row[1] == null ? null : (BigDecimal) row[1];
        return new PriceRange(min, max);
    }

    /**
     * Prefix matches rank before substring matches (a case expression, not a
     * secondary sort key removal), deduplicated by product, deterministic
     * tie-break on product id.
     */
    @SuppressWarnings("unchecked")
    public List<ProductSuggestion> suggest(String query, int limit) {
        String sql =
                """
                SELECT p.id, p.name, p.slug FROM products p
                WHERE p.name ILIKE CONCAT('%', :query, '%')
                ORDER BY
                  CASE WHEN p.name ILIKE CONCAT(:query, '%') THEN 0 ELSE 1 END ASC,
                  p.name ASC,
                  p.id ASC
                LIMIT :limit
                """;
        Query nativeQuery = entityManager.createNativeQuery(sql);
        nativeQuery.setParameter("query", query);
        nativeQuery.setParameter("limit", limit);
        List<Object[]> rows = nativeQuery.getResultList();
        return rows.stream()
                .map(row -> new ProductSuggestion(((Number) row[0]).longValue(), (String) row[1], (String) row[2]))
                .toList();
    }

    private void bindCriteria(Query query, CatalogSearchCriteria criteria) {
        query.setParameter("query", criteria.query());
        query.setParameter("categoryId", criteria.categoryId());
        query.setParameter("minPrice", criteria.minPrice());
        query.setParameter("maxPrice", criteria.maxPrice());
        query.setParameter("attributesJson", toAttributesJson(criteria.attributes()));
        query.setParameter("inStockFilter", criteria.inStock());
    }

    private String toAttributesJson(List<AttributeFilter> attributes) {
        Map<String, String> combined = new LinkedHashMap<>();
        if (attributes != null) {
            for (AttributeFilter attribute : attributes) {
                combined.put(attribute.name(), attribute.value());
            }
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize attribute filters", e);
        }
    }
}
