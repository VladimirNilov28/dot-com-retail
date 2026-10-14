package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.entities.category.Category;
import ee.bytecore.backend.entities.inventory.Warehouse;
import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.entities.product.ProductVariant;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@SpringBootTest(properties = {"spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.listener.auto-startup=false"})
@Import(PostgresTestConfiguration.class)
@Tag("integration")
@Transactional
class ProductRecommendationIntegrationTest {

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

    @Autowired
    EntityManager entityManager;

    private Warehouse warehouse;
    private int sku;

    @BeforeEach
    void setUp() {
        warehouse = warehouseService.create("Recommendation Warehouse", "Tallinn");
    }

    private Product product(String slug, List<Long> categories, int stock) {
        Product product = productService.create(slug, "rec-" + slug, null, categories);
        ProductVariant variant = productVariantService.create(
                product.getId(), "REC-" + slug.toUpperCase() + "-" + sku++, new BigDecimal("10.00"), null, null, null);
        inventoryService.setInventory(variant.getId(), warehouse.getId(), stock);
        return product;
    }

    private void brand(Long brandId, Product... products) {
        entityManager.flush();
        for (Product product : products) {
            entityManager
                    .createNativeQuery("UPDATE products SET brand_id = :brand WHERE id = :id")
                    .setParameter("brand", brandId)
                    .setParameter("id", product.getId())
                    .executeUpdate();
        }
    }

    private Long newBrand(String name) {
        return ((Number) entityManager
                        .createNativeQuery("INSERT INTO brands (name) VALUES (:name) RETURNING id")
                        .setParameter("name", name)
                        .getSingleResult())
                .longValue();
    }

    @Test
    void shouldRecommendInStockProductsSharingCategoryThenBrandExcludingInputsTest() {
        Category cpus = categoryService.create("Rec CPUs", "rec-cpus", null);
        Category coolers = categoryService.create("Rec Coolers", "rec-coolers", null);
        Product seed = product("seed-cpu", List.of(cpus.getId()), 3);
        Product sameCategory = product("other-cpu", List.of(cpus.getId()), 2);
        Product sameCategoryOutOfStock = product("sold-out-cpu", List.of(cpus.getId()), 0);
        Product sameBrandOnly = product("brand-cooler", List.of(coolers.getId()), 4);
        Product unrelated = product("unrelated-cooler", List.of(coolers.getId()), 4);
        Long brand = newBrand("Rec Brand");
        brand(brand, seed, sameBrandOnly);

        List<Product> result = catalogSearchService.recommend(List.of(seed.getId()), 8);

        assertThat(result).extracting(Product::getId).containsExactly(sameCategory.getId(), sameBrandOnly.getId());
        assertThat(result)
                .extracting(Product::getId)
                .doesNotContain(seed.getId(), sameCategoryOutOfStock.getId(), unrelated.getId());
    }

    @Test
    void shouldOrderTiesByIdAndRespectLimitTest() {
        Category gpus = categoryService.create("Rec GPUs", "rec-gpus", null);
        Product seed = product("seed-gpu", List.of(gpus.getId()), 1);
        Product first = product("gpu-a", List.of(gpus.getId()), 1);
        product("gpu-b", List.of(gpus.getId()), 1);

        assertThat(catalogSearchService.recommend(List.of(seed.getId(), seed.getId()), 1))
                .extracting(Product::getId)
                .containsExactly(first.getId());
    }

    @Test
    void shouldReturnEmptyForNoInputsAndRejectNonPositiveLimitTest() {
        assertThat(catalogSearchService.recommend(List.of(), null)).isEmpty();
        assertThatThrownBy(() -> catalogSearchService.recommend(List.of(1L), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
