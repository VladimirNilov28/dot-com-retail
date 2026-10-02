package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.config.PostgresTestConfiguration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A mocked CategoryRepository never invokes Hibernate's Bean Validation or
 * real unique-constraint enforcement, so it can't demonstrate what actually
 * happens when persistence runs for real. Needs a real Postgres/Hibernate
 * context — see "Repository testing" in CLAUDE.md.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Tag("integration")
class CategoryServiceIntegrationTest {

    @Autowired
    CategoryService categoryService;

    @Test
    @Transactional
    void shouldNotLeakHibernateValidationInternalsForOversizedNameTest() {
        String oversizedName = "x".repeat(300);

        assertThatThrownBy(() -> categoryService.create(oversizedName, "oversized-name-slug", null))
                .satisfies(error -> assertThat(error.getMessage())
                        .as("an oversized name must not leak Hibernate/Jakarta Validation internals")
                        .doesNotContain("ConstraintViolationException")
                        .doesNotContain("jakarta.validation")
                        .doesNotContain("ConstraintViolationImpl"));
    }

    @Test
    @Transactional
    void shouldNotLeakDataIntegrityViolationForDuplicateSlugTest() {
        categoryService.create("Original", "duplicate-slug-test", null);

        assertThatThrownBy(() -> categoryService.create("Duplicate", "duplicate-slug-test", null))
                .satisfies(error -> assertThat(error.getMessage())
                        .as("a duplicate slug must not leak the raw SQL/constraint-violation internals")
                        .doesNotContain("DataIntegrityViolationException")
                        .doesNotContain("insert into")
                        .doesNotContain("ERROR:"));
    }
}
