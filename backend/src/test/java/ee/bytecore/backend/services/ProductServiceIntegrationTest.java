package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.config.PostgresTestConfiguration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A mocked ProductRepository never invokes Hibernate's Bean Validation or
 * real unique-constraint enforcement, so it can't demonstrate what actually
 * happens when persistence runs for real. Needs a real Postgres/Hibernate
 * context — see "Repository testing" in CLAUDE.md.
 */
@SpringBootTest(properties = {"spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.listener.auto-startup=false"})
@Import(PostgresTestConfiguration.class)
@Tag("integration")
class ProductServiceIntegrationTest {

    @Autowired
    ProductService productService;

    @Test
    @Transactional
    void shouldNotLeakHibernateValidationInternalsForOversizedProductNameTest() {
        String oversizedName = "x".repeat(300);

        assertThatThrownBy(() -> productService.create(oversizedName, "oversized-product-name-slug", null, null))
                .satisfies(error -> assertThat(error.getMessage())
                        .as("an oversized name must not leak Hibernate/Jakarta Validation internals")
                        .doesNotContain("ConstraintViolationException")
                        .doesNotContain("jakarta.validation")
                        .doesNotContain("ConstraintViolationImpl"));
    }

    @Test
    @Transactional
    void shouldNotLeakDataIntegrityViolationForDuplicateProductSlugTest() {
        productService.create("Original", "duplicate-product-slug-test", null, List.of());

        assertThatThrownBy(() -> productService.create("Duplicate", "duplicate-product-slug-test", null, List.of()))
                .satisfies(error -> assertThat(error.getMessage())
                        .as("a duplicate slug must not leak the raw SQL/constraint-violation internals")
                        .doesNotContain("DataIntegrityViolationException")
                        .doesNotContain("insert into")
                        .doesNotContain("ERROR:"));
    }
}
