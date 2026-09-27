package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.entities.product.Product;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A mocked ProductVariantRepository never invokes real unique-constraint
 * enforcement, so it can't demonstrate what actually happens when
 * persistence runs for real. Needs a real Postgres/Hibernate context — see
 * "Repository testing" in CLAUDE.md.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Tag("integration")
class ProductVariantServiceIntegrationTest {

    @Autowired
    ProductService productService;

    @Autowired
    ProductVariantService productVariantService;

    @Test
    @Transactional
    void shouldNotLeakDataIntegrityViolationForDuplicateSkuTest() {
        Product product = productService.create("T-Shirt", "duplicate-sku-product", null, null);

        productVariantService.create(product.getId(), "DUPLICATE-SKU", new BigDecimal("19.99"), null, null, null);

        assertThatThrownBy(() -> productVariantService.create(
                        product.getId(), "DUPLICATE-SKU", new BigDecimal("24.99"), null, null, null))
                .satisfies(error -> assertThat(error.getMessage())
                        .as("a duplicate SKU must not leak the raw SQL/constraint-violation internals")
                        .doesNotContain("DataIntegrityViolationException")
                        .doesNotContain("insert into")
                        .doesNotContain("ERROR:"));
    }
}
