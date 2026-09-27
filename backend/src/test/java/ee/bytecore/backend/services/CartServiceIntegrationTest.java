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
import ee.bytecore.backend.entities.cart.Cart;
import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.repositories.cart.CartRepository;
import ee.bytecore.backend.repositories.user.UserRepository;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A mocked CartItemRepository never invokes the real "cart_id,
 * product_variant_id" unique constraint, so it can't demonstrate what
 * actually happens when persistence runs for real. Needs a real
 * Postgres/Hibernate context — see "Repository testing" in CLAUDE.md.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Tag("integration")
class CartServiceIntegrationTest {

    @Autowired
    UserRepository userRepository;

    @Autowired
    CartRepository cartRepository;

    @Autowired
    ProductService productService;

    @Autowired
    ProductVariantService productVariantService;

    @Autowired
    CartService cartService;

    @Test
    @Transactional
    void shouldNotLeakDataIntegrityViolationForDuplicateCartItemTest() {
        User user = userRepository.save(
                User.create("cart-test-user", "cart-test@example.com", LocalDate.of(1990, 1, 1)));
        cartRepository.save(Cart.create(user));
        Product product = productService.create("T-Shirt", "cart-test-product", null, null);
        ProductVariant variant =
                productVariantService.create(product.getId(), "CART-TEST-SKU", new BigDecimal("9.99"), null, null, null);

        cartService.addItem(user.getId(), variant.getId(), 1);

        assertThatThrownBy(() -> cartService.addItem(user.getId(), variant.getId(), 2))
                .satisfies(error -> assertThat(error.getMessage())
                        .as("adding the same product variant twice must not leak raw SQL/constraint internals")
                        .doesNotContain("DataIntegrityViolationException")
                        .doesNotContain("insert into")
                        .doesNotContain("ERROR:"));
    }
}
