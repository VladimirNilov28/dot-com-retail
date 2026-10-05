package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.entities.category.Category;
import ee.bytecore.backend.entities.inventory.Warehouse;
import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.services.catalog.AttributeFilter;
import ee.bytecore.backend.services.catalog.CatalogSearchCriteria;
import ee.bytecore.backend.services.catalog.CatalogSearchResult;
import ee.bytecore.backend.services.catalog.ProductSortOption;
import ee.bytecore.backend.services.catalog.ProductSuggestion;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Requires a real Postgres/Hibernate context: search relies on ILIKE, JSONB
 * containment (@>), jsonb_each_text facet expansion, and aggregate inventory
 * sums — none of which a mocked repository can meaningfully exercise. See
 * "Repository testing" in CLAUDE.md.
 */
@SpringBootTest(properties = {"spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.listener.auto-startup=false"})
@Import(PostgresTestConfiguration.class)
@Tag("integration")
class CatalogSearchServiceIntegrationTest {

    @Autowired
    CatalogSearchService catalogSearchService;

    @Autowired
    ProductService productService;

    @Autowired
    ProductVariantService productVariantService;

    @Autowired
    CategoryService categoryService;

    @Autowired
    WarehouseService warehouseService;

    @Autowired
    InventoryService inventoryService;

    private CatalogSearchCriteria criteria(String query) {
        return new CatalogSearchCriteria(query, null, null, null, null, null, ProductSortOption.RELEVANCE, 0, 20);
    }

    @Test
    @Transactional
    void shouldFindProductByExactNameTest() {
        productService.create("Wireless Mouse", "wireless-mouse-exact", null, null);

        CatalogSearchResult result = catalogSearchService.search(criteria("Wireless Mouse"));

        assertThat(result.items()).extracting(Product::getName).contains("Wireless Mouse");
    }

    @Test
    @Transactional
    void shouldFindProductByPartialNameCaseInsensitiveTest() {
        productService.create("Bluetooth Keyboard", "bluetooth-keyboard-partial", null, null);

        CatalogSearchResult result = catalogSearchService.search(criteria("bluetooth"));

        assertThat(result.items()).extracting(Product::getName).contains("Bluetooth Keyboard");
    }

    @Test
    @Transactional
    void shouldFindProductByDescriptionTest() {
        productService.create(
                "Standing Desk", "standing-desk-description", "An ergonomic adjustable-height workstation", null);

        CatalogSearchResult result = catalogSearchService.search(criteria("ergonomic"));

        assertThat(result.items()).extracting(Product::getName).contains("Standing Desk");
    }

    @Test
    @Transactional
    void shouldFindProductBySkuTest() {
        Product product = productService.create("Desk Lamp", "desk-lamp-sku", null, null);
        productVariantService.create(product.getId(), "LAMP-SKU-42", new BigDecimal("15.00"), null, null, null);

        CatalogSearchResult result = catalogSearchService.search(criteria("LAMP-SKU-42"));

        assertThat(result.items()).extracting(Product::getName).contains("Desk Lamp");
    }

    @Test
    @Transactional
    void shouldReturnEmptyResultsWhenNoMatchTest() {
        productService.create("Office Chair", "office-chair-no-match", null, null);

        CatalogSearchResult result = catalogSearchService.search(criteria("nonexistent-search-term-xyz"));

        assertThat(result.items()).isEmpty();
        assertThat(result.pageInfo().totalItems()).isZero();
    }

    @Test
    @Transactional
    void shouldFilterByDirectCategoryTest() {
        Category electronics = categoryService.create("Electronics", "electronics-filter-test", null);
        Category furniture = categoryService.create("Furniture", "furniture-filter-test", null);
        productService.create("Category Filter Laptop", "category-filter-laptop", null, List.of(electronics.getId()));
        productService.create("Category Filter Sofa", "category-filter-sofa", null, List.of(furniture.getId()));

        CatalogSearchCriteria byElectronics = new CatalogSearchCriteria(
                null, electronics.getId(), null, null, null, null, ProductSortOption.RELEVANCE, 0, 20);

        CatalogSearchResult result = catalogSearchService.search(byElectronics);

        assertThat(result.items()).extracting(Product::getName).contains("Category Filter Laptop");
        assertThat(result.items()).extracting(Product::getName).doesNotContain("Category Filter Sofa");
    }

    @Test
    @Transactional
    void shouldRejectUnknownCategoryIdTest() {
        CatalogSearchCriteria unknownCategory =
                new CatalogSearchCriteria(null, 999999L, null, null, null, null, ProductSortOption.RELEVANCE, 0, 20);

        assertThatThrownBy(() -> catalogSearchService.search(unknownCategory))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Transactional
    void shouldMatchProductWhenAtLeastOneVariantIsWithinPriceRangeTest() {
        Product product = productService.create("Price Range Headphones", "price-range-headphones", null, null);
        productVariantService.create(product.getId(), "HEADPHONES-CHEAP", new BigDecimal("20.00"), null, null, null);
        productVariantService.create(
                product.getId(), "HEADPHONES-EXPENSIVE", new BigDecimal("500.00"), null, null, null);

        CatalogSearchCriteria priceFilter = new CatalogSearchCriteria(
                null,
                null,
                new BigDecimal("10.00"),
                new BigDecimal("50.00"),
                null,
                null,
                ProductSortOption.RELEVANCE,
                0,
                20);

        CatalogSearchResult result = catalogSearchService.search(priceFilter);

        assertThat(result.items()).extracting(Product::getName).contains("Price Range Headphones");
    }

    @Test
    @Transactional
    void shouldRejectMinPriceGreaterThanMaxPriceTest() {
        CatalogSearchCriteria invalidRange = new CatalogSearchCriteria(
                null,
                null,
                new BigDecimal("50.00"),
                new BigDecimal("10.00"),
                null,
                null,
                ProductSortOption.RELEVANCE,
                0,
                20);

        assertThatThrownBy(() -> catalogSearchService.search(invalidRange))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Transactional
    void shouldRejectNegativeMinPriceTest() {
        CatalogSearchCriteria negativePrice = new CatalogSearchCriteria(
                null, null, new BigDecimal("-5.00"), null, null, null, ProductSortOption.RELEVANCE, 0, 20);

        assertThatThrownBy(() -> catalogSearchService.search(negativePrice))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Transactional
    void shouldOnlyMatchWhenSameVariantSatisfiesAllRequestedAttributesTest() {
        Product product = productService.create("Attribute Combo Shirt", "attribute-combo-shirt", null, null);
        ProductVariant blackSmall = productVariantService.create(
                product.getId(),
                "SHIRT-BLACK-S",
                new BigDecimal("25.00"),
                Map.of("color", "black", "size", "S"),
                null,
                null);
        productVariantService.create(
                product.getId(),
                "SHIRT-RED-M",
                new BigDecimal("25.00"),
                Map.of("color", "red", "size", "M"),
                null,
                null);

        CatalogSearchCriteria matchingCombo = new CatalogSearchCriteria(
                null,
                null,
                null,
                null,
                List.of(new AttributeFilter("color", "black"), new AttributeFilter("size", "S")),
                null,
                ProductSortOption.RELEVANCE,
                0,
                20);
        CatalogSearchCriteria mismatchedCombo = new CatalogSearchCriteria(
                null,
                null,
                null,
                null,
                List.of(new AttributeFilter("color", "black"), new AttributeFilter("size", "M")),
                null,
                ProductSortOption.RELEVANCE,
                0,
                20);

        assertThat(catalogSearchService.search(matchingCombo).items())
                .extracting(Product::getName)
                .contains("Attribute Combo Shirt");
        assertThat(catalogSearchService.search(mismatchedCombo).items())
                .extracting(Product::getName)
                .doesNotContain("Attribute Combo Shirt");
        assertThat(blackSmall.getAttributes()).containsEntry("color", "black");
    }

    @Test
    @Transactional
    void shouldFilterByAvailabilityTest() {
        Warehouse warehouse = warehouseService.create("Search Test Warehouse", "Somewhere");

        Product inStockProduct = productService.create("In Stock Widget", "in-stock-widget", null, null);
        ProductVariant inStockVariant = productVariantService.create(
                inStockProduct.getId(), "WIDGET-IN-STOCK", new BigDecimal("9.99"), null, null, null);
        inventoryService.setInventory(inStockVariant.getId(), warehouse.getId(), 5);

        Product outOfStockProduct = productService.create("Out Of Stock Widget", "out-of-stock-widget", null, null);
        ProductVariant outOfStockVariant = productVariantService.create(
                outOfStockProduct.getId(), "WIDGET-OUT-OF-STOCK", new BigDecimal("9.99"), null, null, null);
        inventoryService.setInventory(outOfStockVariant.getId(), warehouse.getId(), 0);

        CatalogSearchCriteria inStockOnly =
                new CatalogSearchCriteria(null, null, null, null, null, true, ProductSortOption.RELEVANCE, 0, 20);

        CatalogSearchResult result = catalogSearchService.search(inStockOnly);

        assertThat(result.items()).extracting(Product::getName).contains("In Stock Widget");
        assertThat(result.items()).extracting(Product::getName).doesNotContain("Out Of Stock Widget");
    }

    @Test
    @Transactional
    void shouldSortByPriceAscendingUsingMinimumMatchingVariantPriceTest() {
        Product cheap = productService.create("Sort Price Cheap", "sort-price-cheap", null, null);
        productVariantService.create(cheap.getId(), "SORT-CHEAP", new BigDecimal("5.00"), null, null, null);
        Product expensive = productService.create("Sort Price Expensive", "sort-price-expensive", null, null);
        productVariantService.create(expensive.getId(), "SORT-EXPENSIVE", new BigDecimal("500.00"), null, null, null);

        CatalogSearchCriteria priceAsc = new CatalogSearchCriteria(
                "Sort Price", null, null, null, null, null, ProductSortOption.PRICE_ASC, 0, 20);

        CatalogSearchResult result = catalogSearchService.search(priceAsc);

        assertThat(result.items())
                .extracting(Product::getName)
                .containsSubsequence("Sort Price Cheap", "Sort Price Expensive");
    }

    @Test
    @Transactional
    void shouldSortByPriceDescendingTest() {
        Product cheap = productService.create("Desc Sort Cheap", "desc-sort-cheap", null, null);
        productVariantService.create(cheap.getId(), "DESC-CHEAP", new BigDecimal("5.00"), null, null, null);
        Product expensive = productService.create("Desc Sort Expensive", "desc-sort-expensive", null, null);
        productVariantService.create(expensive.getId(), "DESC-EXPENSIVE", new BigDecimal("500.00"), null, null, null);

        CatalogSearchCriteria priceDesc = new CatalogSearchCriteria(
                "Desc Sort", null, null, null, null, null, ProductSortOption.PRICE_DESC, 0, 20);

        CatalogSearchResult result = catalogSearchService.search(priceDesc);

        assertThat(result.items())
                .extracting(Product::getName)
                .containsSubsequence("Desc Sort Expensive", "Desc Sort Cheap");
    }

    @Test
    @Transactional
    void shouldPaginateResultsDeterministicallyTest() {
        for (int i = 0; i < 5; i++) {
            productService.create("Paginated Widget " + i, "paginated-widget-" + i, null, null);
        }

        CatalogSearchCriteria firstPage = new CatalogSearchCriteria(
                "Paginated Widget", null, null, null, null, null, ProductSortOption.RELEVANCE, 0, 2);
        CatalogSearchCriteria secondPage = new CatalogSearchCriteria(
                "Paginated Widget", null, null, null, null, null, ProductSortOption.RELEVANCE, 1, 2);

        CatalogSearchResult first = catalogSearchService.search(firstPage);
        CatalogSearchResult second = catalogSearchService.search(secondPage);

        assertThat(first.items()).hasSize(2);
        assertThat(second.items()).hasSize(2);
        assertThat(first.items())
                .extracting(Product::getId)
                .doesNotContainAnyElementsOf(
                        second.items().stream().map(Product::getId).toList());
        assertThat(first.pageInfo().totalItems()).isEqualTo(5);
        assertThat(first.pageInfo().totalPages()).isEqualTo(3);
    }

    @Test
    @Transactional
    void shouldRejectNegativePageTest() {
        CatalogSearchCriteria negativePage =
                new CatalogSearchCriteria(null, null, null, null, null, null, ProductSortOption.RELEVANCE, -1, 20);

        assertThatThrownBy(() -> catalogSearchService.search(negativePage))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Transactional
    void shouldRejectSizeAboveMaxTest() {
        CatalogSearchCriteria oversizedPage =
                new CatalogSearchCriteria(null, null, null, null, null, null, ProductSortOption.RELEVANCE, 0, 1000);

        assertThatThrownBy(() -> catalogSearchService.search(oversizedPage))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Transactional
    void shouldComputeCategoryAndPriceFacetsOverFilteredPopulationTest() {
        Category books = categoryService.create("Books", "books-facet-test", null);
        Product product = productService.create("Faceted Novel", "faceted-novel", null, List.of(books.getId()));
        productVariantService.create(product.getId(), "NOVEL-SKU", new BigDecimal("12.50"), null, null, null);

        CatalogSearchResult result = catalogSearchService.search(criteria("Faceted Novel"));

        assertThat(result.facets().categories()).extracting(f -> f.name()).contains("Books");
        assertThat(result.facets().price().min()).isEqualByComparingTo("12.50");
        assertThat(result.facets().price().max()).isEqualByComparingTo("12.50");
    }

    @Test
    @Transactional
    void shouldReturnPrefixSuggestionsBeforeSubstringSuggestionsTest() {
        productService.create("Tripod for iPhone", "tripod-for-iphone-suggestion", null, null);
        productService.create("IPhone Case", "iphone-case-suggestion", null, null);

        List<ProductSuggestion> suggestions = catalogSearchService.suggest("iph", null);

        assertThat(suggestions)
                .extracting(ProductSuggestion::name)
                .containsSubsequence("IPhone Case", "Tripod for iPhone");
    }

    @Test
    @Transactional
    void shouldRejectBlankSuggestionQueryTest() {
        assertThatThrownBy(() -> catalogSearchService.suggest("   ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Transactional
    void shouldRespectSuggestionLimitTest() {
        for (int i = 0; i < 5; i++) {
            productService.create("Limited Suggestion " + i, "limited-suggestion-" + i, null, null);
        }

        List<ProductSuggestion> suggestions = catalogSearchService.suggest("Limited Suggestion", 2);

        assertThat(suggestions).hasSize(2);
    }
}
