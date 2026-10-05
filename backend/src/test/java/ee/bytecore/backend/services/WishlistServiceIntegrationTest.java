package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.entities.wishlist.Wishlist;
import ee.bytecore.backend.repositories.user.UserRepository;
import ee.bytecore.backend.repositories.wishlist.WishlistRepository;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A mocked WishlistItemRepository never invokes the real "wishlist_id,
 * product_variant_id" unique constraint, so it can't demonstrate what
 * actually happens when persistence runs for real. Needs a real
 * Postgres/Hibernate context — see "Repository testing" in CLAUDE.md.
 */
@SpringBootTest(properties = {"spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.listener.auto-startup=false"})
@Import(PostgresTestConfiguration.class)
@Tag("integration")
class WishlistServiceIntegrationTest {

    @Autowired
    UserRepository userRepository;

    @Autowired
    WishlistRepository wishlistRepository;

    @Autowired
    ProductService productService;

    @Autowired
    ProductVariantService productVariantService;

    @Autowired
    WishlistService wishlistService;

    @Test
    @Transactional
    void shouldNotLeakDataIntegrityViolationForDuplicateWishlistItemTest() {
        User user = userRepository.save(
                User.create("wishlist-test-user", "wishlist-test@example.com", LocalDate.of(1990, 1, 1)));
        wishlistRepository.save(Wishlist.create(user));
        Product product = productService.create("T-Shirt", "wishlist-test-product", null, null);
        ProductVariant variant = productVariantService.create(
                product.getId(), "WISHLIST-TEST-SKU", new BigDecimal("9.99"), null, null, null);

        wishlistService.addItem(user.getId(), variant.getId());

        assertThatThrownBy(() -> wishlistService.addItem(user.getId(), variant.getId()))
                .satisfies(error -> assertThat(error.getMessage())
                        .as("adding the same product variant twice must not leak raw SQL/constraint internals")
                        .doesNotContain("DataIntegrityViolationException")
                        .doesNotContain("insert into")
                        .doesNotContain("ERROR:"));
    }
}
