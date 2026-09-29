package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.entities.cart.Cart;
import ee.bytecore.backend.entities.cart.CartItem;
import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.repositories.cart.CartItemRepository;
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
    CartItemRepository cartItemRepository;

    @Autowired
    ProductService productService;

    @Autowired
    ProductVariantService productVariantService;

    @Autowired
    CartService cartService;

    @Test
    @Transactional
    void shouldMergeQuantityWhenAddingSameProductVariantTwiceTest() {
        User user =
                userRepository.save(User.create("cart-test-user", "cart-test@example.com", LocalDate.of(1990, 1, 1)));
        cartRepository.save(Cart.create(user));
        Product product = productService.create("T-Shirt", "cart-test-product", null, null);
        ProductVariant variant = productVariantService.create(
                product.getId(), "CART-TEST-SKU", new BigDecimal("9.99"), null, null, null);

        cartService.addItem(user.getId(), variant.getId(), 1);
        CartItem item = cartService.addItem(user.getId(), variant.getId(), 2);

        assertThat(item.getQuantity()).isEqualTo(3);
        assertThat(cartItemRepository.findAllByCartId(item.getCart().getId())).hasSize(1);
    }

    @Test
    void shouldLazilyCreateCartWhenMissingTest() {
        User user =
                userRepository.save(User.create("lazy-cart-user", "lazy-cart@example.com", LocalDate.of(1990, 1, 1)));

        Cart cart = cartService.getMyCart(user.getId());

        assertThat(cart.getUser().getId()).isEqualTo(user.getId());
        assertThat(cart.getItems()).isEmpty();
        assertThat(cartRepository.findByUserId(user.getId())).isPresent();
    }

    @Test
    void shouldReturnSameCartOnRepeatedMyCartCallsTest() {
        User user = userRepository.save(
                User.create("idempotent-cart-user", "idempotent-cart@example.com", LocalDate.of(1990, 1, 1)));

        Cart first = cartService.getMyCart(user.getId());
        Cart second = cartService.getMyCart(user.getId());

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(cartRepository.findAll().stream()
                        .filter(c -> user.getId().equals(c.getUser().getId()))
                        .count())
                .isEqualTo(1);
    }

    /**
     * Not {@code @Transactional} at the method level - both threads must
     * genuinely commit/attempt-commit their own cart-creation transaction
     * against the same connection pool for the unique constraint race to be
     * exercised, matching the {@code REQUIRES_NEW} behavior used by
     * production code.
     */
    @Test
    void shouldCreateExactlyOneCartForConcurrentFirstAccessTest() throws InterruptedException {
        User user = userRepository.save(
                User.create("concurrent-cart-user", "concurrent-cart@example.com", LocalDate.of(1990, 1, 1)));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();

        try {
            var futureA = executor.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    cartService.getMyCart(user.getId());
                } catch (RuntimeException e) {
                    errors.incrementAndGet();
                }
            });
            var futureB = executor.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    cartService.getMyCart(user.getId());
                } catch (RuntimeException e) {
                    errors.incrementAndGet();
                }
            });

            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            futureA.get(30, TimeUnit.SECONDS);
            futureB.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            executor.shutdownNow();
        }

        assertThat(errors.get()).isZero();
        List<Cart> carts = cartRepository.findAll().stream()
                .filter(c -> user.getId().equals(c.getUser().getId()))
                .toList();
        assertThat(carts).hasSize(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
