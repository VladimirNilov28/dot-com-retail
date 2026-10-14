package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.services.ProductImportService.Format;
import ee.bytecore.backend.services.ProductImportService.Result;
import ee.bytecore.backend.services.ProductImportService.RowError;
import ee.bytecore.backend.services.audit.AuditService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Bulk product import against real PostgreSQL: validation rules and the all-or-nothing policy. */
@SpringBootTest(
        properties = {
            "spring.kafka.bootstrap-servers=127.0.0.1:1",
            "spring.kafka.listener.auto-startup=false",
            "spring.docker.compose.enabled=false"
        })
@Import(PostgresTestConfiguration.class)
@Tag("integration")
@Timeout(60)
class ProductImportServiceIntegrationTest {

    @Autowired
    ProductImportService importService;

    @Autowired
    AuditService auditService;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    ProductVariantService variantService;

    private String p;
    private String category;

    @BeforeEach
    void setUp() {
        p = "imp" + UUID.randomUUID().toString().substring(0, 8);
        category = p + "-cat";
        jdbc.update("INSERT INTO categories(name, slug) VALUES (?, ?)", "Import " + p, category);
    }

    private int productCount() {
        return jdbc.queryForObject("SELECT count(*) FROM products WHERE slug LIKE ?", Integer.class, p + "%");
    }

    private int variantCount() {
        return jdbc.queryForObject("SELECT count(*) FROM product_variants WHERE sku LIKE ?", Integer.class, p + "%");
    }

    private String csv(String... rows) {
        return "slug,name,description,brand,categories,sku,price,weight_grams,length_mm,width_mm,height_mm,active,attr:size\n"
                + String.join("\n", rows)
                + "\n";
    }

    @Test
    void validCsvCreatesProductsVariantsBrandAndCategoryLinks() {
        Result result = importService.importProducts(
                Format.CSV,
                csv(
                        p + "-tee,Tee,\"Soft, cotton\"," + p + "Brand," + category + "," + p
                                + "-TEE-S,19.99,180,300,200,20,true,S",
                        p + "-tee,Tee,\"Soft, cotton\"," + p + "Brand," + category + "," + p
                                + "-TEE-M,19.99,190,,,,no,M",
                        p + "-cap,Cap,,,," + p + "-CAP,9.5,,,,,,"));

        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(result.productsCreated()).isEqualTo(2);
        assertThat(result.variantsCreated()).isEqualTo(3);
        assertThat(result.brandsCreated()).isEqualTo(1);
        assertThat(productCount()).isEqualTo(2);
        assertThat(variantCount()).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                        "SELECT is_active FROM product_variants WHERE sku=?", Boolean.class, p + "-TEE-M"))
                .isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT attributes->>'size' FROM product_variants WHERE sku=?", String.class, p + "-TEE-S"))
                .isEqualTo("S");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM product_categories pc JOIN products pr ON pr.id=pc.product_id"
                                + " WHERE pr.slug=?",
                        Integer.class,
                        p + "-tee"))
                .isEqualTo(1);
    }

    @Test
    void validJsonCreatesEverything() {
        String json =
                """
                {"products":[{"slug":"%1$s-mug","name":"Mug","brand":"%1$sBrand","categories":["%2$s"],
                  "variants":[{"sku":"%1$s-MUG-W","price":"12.00","attributes":{"color":"white"}},
                              {"sku":"%1$s-MUG-B","price":12.5,"active":false}]}]}
                """
                        .formatted(p, category);

        Result result = importService.importProducts(Format.JSON, json);

        assertThat(result.errors()).isEmpty();
        assertThat(result.productsCreated()).isEqualTo(1);
        assertThat(result.variantsCreated()).isEqualTo(2);
        assertThat(variantCount()).isEqualTo(2);
    }

    @Test
    void anyInvalidRowImportsNothingAndReportsRowFieldMessage() {
        jdbc.update("INSERT INTO products(name, slug) VALUES ('Existing', ?)", p + "-existing");
        int before = productCount();

        Result result = importService.importProducts(
                Format.CSV,
                csv(
                        p + "-ok,Okay,,,," + p + "-OK,10.00,,,,,,",
                        p + "-bad,Bad,,,," + p + "-OK,10.001,,,,,,",
                        p + "-existing,Clash,,,unknown-" + p + "," + p + "-X,0,,10,,,,",
                        "Not A Slug,,,,," + p + "-Y,5,,,,,maybe,"));

        assertThat(result.success()).isFalse();
        assertThat(result.productsCreated()).isZero();
        assertThat(productCount()).isEqualTo(before);
        assertThat(variantCount()).isZero();
        assertThat(result.errors())
                .extracting(RowError::row, RowError::field)
                .contains(
                        org.assertj.core.groups.Tuple.tuple(2, "price"),
                        org.assertj.core.groups.Tuple.tuple(3, "price"),
                        org.assertj.core.groups.Tuple.tuple(4, "slug"),
                        org.assertj.core.groups.Tuple.tuple(4, "name"),
                        org.assertj.core.groups.Tuple.tuple(4, "active"));
        assertThat(result.errors()).allSatisfy(e -> assertThat(e.message()).isNotBlank());
    }

    @Test
    void databaseConflictsAreReportedWithoutWriting() {
        jdbc.update("INSERT INTO products(name, slug) VALUES ('Existing', ?)", p + "-taken");
        Long existingId = jdbc.queryForObject("SELECT id FROM products WHERE slug=?", Long.class, p + "-taken");
        jdbc.update("INSERT INTO product_variants(product_id, sku, price) VALUES (?, ?, 1)", existingId, p + "-DBSKU");

        Result result = importService.importProducts(
                Format.CSV,
                csv(
                        p + "-taken,Taken,,,," + p + "-T1,10.00,,,,,,",
                        p + "-new,New,,,unknown-" + p + "," + p + "-T2,10.00,,,,,,",
                        p + "-dup,Dup,,,," + p + "-T2,10.00,,,,,,",
                        p + "-dbsku,DbSku,,,," + p + "-DBSKU,10.00,,,,,,"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors())
                .extracting(RowError::row, RowError::field)
                .contains(
                        org.assertj.core.groups.Tuple.tuple(1, "slug"),
                        org.assertj.core.groups.Tuple.tuple(2, "categories"),
                        org.assertj.core.groups.Tuple.tuple(3, "sku"),
                        org.assertj.core.groups.Tuple.tuple(4, "sku"));
        assertThat(variantCount()).isEqualTo(1);
    }

    @Test
    void failureDuringCreationRollsBackEarlierRows() {
        doThrow(new IllegalStateException("simulated failure"))
                .when(variantService)
                .create(anyLong(), eq(p + "-FAIL"), any(BigDecimal.class), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> importService.importProducts(
                        Format.CSV,
                        csv(
                                p + "-first,First," + ",NewBrand" + p + ",," + p + "-FIRST,10.00,,,,,,",
                                p + "-second,Second,,,," + p + "-FAIL,10.00,,,,,,")))
                .hasMessageContaining("simulated failure");

        assertThat(productCount()).isZero();
        assertThat(variantCount()).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM brands WHERE lower(name)=lower(?)", Integer.class, "NewBrand" + p))
                .isZero();
    }

    @Test
    void auditRecordIsWrittenAndSearchable() {
        auditService.record("importProducts", "PRODUCT_IMPORT", null, "SUCCESS", java.util.Map.of("marker", p));

        var page = auditService.search("importProducts", "PRODUCT_IMPORT", "system", 0, 100);

        assertThat(page.items()).anySatisfy(entry -> {
            assertThat(entry.actorSubject()).isEqualTo("system");
            assertThat(entry.details()).containsEntry("marker", p);
        });
    }
}
