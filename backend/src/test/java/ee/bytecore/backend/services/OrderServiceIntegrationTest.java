package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import ee.bytecore.backend.config.KafkaTestConfiguration;
import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.entities.cart.Cart;
import ee.bytecore.backend.entities.cart.CartItem;
import ee.bytecore.backend.entities.inventory.Inventory;
import ee.bytecore.backend.entities.inventory.Warehouse;
import ee.bytecore.backend.entities.payment.Order;
import ee.bytecore.backend.entities.payment.OrderItem;
import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.enums.OrderStatus;
import ee.bytecore.backend.exceptions.InsufficientStockException;
import ee.bytecore.backend.repositories.cart.CartItemRepository;
import ee.bytecore.backend.repositories.cart.CartRepository;
import ee.bytecore.backend.repositories.inventory.InventoryRepository;
import ee.bytecore.backend.repositories.payment.OrderItemRepository;
import ee.bytecore.backend.repositories.payment.OrderRepository;
import ee.bytecore.backend.repositories.user.UserRepository;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Needs a real Postgres/Hibernate context: pessimistic locking, unique
 * constraints, and real transaction rollback semantics can't be exercised
 * against a mocked repository. See "Repository testing" in CLAUDE.md.
 *
 * Deliberately not {@code @Transactional} at the test-method level: each
 * scenario relies on {@code createOrder}/{@code updateStatus} genuinely
 * committing or rolling back their own top-level transaction (matching real
 * request handling), so assertions read real post-commit database state
 * instead of an in-memory, not-yet-flushed persistence context. Test data is
 * kept isolated via unique per-test users/variants/warehouses instead of
 * relying on automatic rollback.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class})
@Tag("integration")
class OrderServiceIntegrationTest {

    @Autowired
    UserRepository userRepository;

    @Autowired
    CartRepository cartRepository;

    @Autowired
    CartItemRepository cartItemRepository;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    OrderItemRepository orderItemRepository;

    @Autowired
    InventoryRepository inventoryRepository;

    @Autowired
    ProductService productService;

    @Autowired
    ProductVariantService productVariantService;

    @Autowired
    WarehouseService warehouseService;

    @Autowired
    InventoryService inventoryService;

    @Autowired
    CartService cartService;

    @Autowired
    OrderService orderService;

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private User createUserWithCart() {
        int n = COUNTER.incrementAndGet();
        User user = userRepository.save(
                User.create("order-test-user-" + n, "order-test-" + n + "@example.com", LocalDate.of(1990, 1, 1)));
        cartRepository.save(Cart.create(user));
        return user;
    }

    private ProductVariant createVariant(BigDecimal price) {
        int n = COUNTER.incrementAndGet();
        Product product = productService.create("Order Test Product " + n, "order-test-product-" + n, null, null);
        return productVariantService.create(product.getId(), "ORDER-TEST-SKU-" + n, price, null, null, null);
    }

    private Warehouse createWarehouse() {
        int n = COUNTER.incrementAndGet();
        return warehouseService.create("Order Test Warehouse " + n, "Somewhere " + n);
    }

    /**
     * Reads cart items via a direct repository query instead of
     * {@code cart.getItems()} - the test method isn't {@code @Transactional},
     * so a Cart entity returned from a prior service call is already
     * detached and its lazy {@code items} collection can't be initialized
     * outside of a transaction.
     */
    private List<CartItem> findCartItems(Long userId) {
        Cart cart = cartRepository.findByUserId(userId).orElseThrow();
        return cartItemRepository.findAllByCartId(cart.getId());
    }

    @Test
    void shouldRejectOrderWithEmptyCartValidationWhenCartRowIsAbsentTest() {
        int n = COUNTER.incrementAndGet();
        User user = userRepository.save(
                User.create("no-cart-user-" + n, "no-cart-" + n + "@example.com", LocalDate.of(1990, 1, 1)));

        assertThatThrownBy(() -> orderService.createOrder(user.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(error -> assertThat(error.getMessage())
                        .as(
                                "a Cart row missing entirely must reach the empty-cart validation, not a 'Cart not found' error")
                        .contains("empty cart"));

        assertThat(cartRepository.findByUserId(user.getId()))
                .as("the cart must have been lazily created even though the order was rejected")
                .isPresent();
        assertThat(orderRepository.findAllByUserId(user.getId())).isEmpty();
    }

    @Test
    void shouldCreateOrderAndDecrementStockWhenSufficientTest() {
        User user = createUserWithCart();
        ProductVariant variant = createVariant(new BigDecimal("9.99"));
        Warehouse warehouse = createWarehouse();
        inventoryService.setInventory(variant.getId(), warehouse.getId(), 5);
        cartService.addItem(user.getId(), variant.getId(), 2);

        Order order = orderService.createOrder(user.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        Inventory inventory = inventoryRepository
                .findByProductVariantIdAndWarehouseId(variant.getId(), warehouse.getId())
                .orElseThrow();
        assertThat(inventory.getQuantity()).isEqualTo(3);
        assertThat(findCartItems(user.getId())).isEmpty();
    }

    @Test
    void shouldCreateOrderWhenStockExactlyMatchesTest() {
        User user = createUserWithCart();
        ProductVariant variant = createVariant(new BigDecimal("9.99"));
        Warehouse warehouse = createWarehouse();
        inventoryService.setInventory(variant.getId(), warehouse.getId(), 5);
        cartService.addItem(user.getId(), variant.getId(), 5);

        orderService.createOrder(user.getId());

        Inventory inventory = inventoryRepository
                .findByProductVariantIdAndWarehouseId(variant.getId(), warehouse.getId())
                .orElseThrow();
        assertThat(inventory.getQuantity()).isZero();
    }

    @Test
    void shouldRejectOrderAndLeaveStockAndCartUnchangedWhenInsufficientTest() {
        User user = createUserWithCart();
        ProductVariant variant = createVariant(new BigDecimal("9.99"));
        Warehouse warehouse = createWarehouse();
        inventoryService.setInventory(variant.getId(), warehouse.getId(), 5);
        cartService.addItem(user.getId(), variant.getId(), 6);

        assertThatThrownBy(() -> orderService.createOrder(user.getId()))
                .isInstanceOf(InsufficientStockException.class)
                .satisfies(error -> assertThat(error.getMessage())
                        .doesNotContain("DataIntegrityViolationException")
                        .doesNotContain("ERROR:"));

        Inventory inventory = inventoryRepository
                .findByProductVariantIdAndWarehouseId(variant.getId(), warehouse.getId())
                .orElseThrow();
        assertThat(inventory.getQuantity()).isEqualTo(5);
        assertThat(findCartItems(user.getId())).hasSize(1);
        assertThat(orderRepository.findAllByUserId(user.getId())).isEmpty();
    }

    @Test
    void shouldDecrementAllVariantsWhenAllAvailableTest() {
        User user = createUserWithCart();
        ProductVariant variantA = createVariant(new BigDecimal("5.00"));
        ProductVariant variantB = createVariant(new BigDecimal("7.00"));
        Warehouse warehouse = createWarehouse();
        inventoryService.setInventory(variantA.getId(), warehouse.getId(), 10);
        inventoryService.setInventory(variantB.getId(), warehouse.getId(), 4);
        cartService.addItem(user.getId(), variantA.getId(), 3);
        cartService.addItem(user.getId(), variantB.getId(), 4);

        orderService.createOrder(user.getId());

        assertThat(inventoryRepository
                        .findByProductVariantIdAndWarehouseId(variantA.getId(), warehouse.getId())
                        .orElseThrow()
                        .getQuantity())
                .isEqualTo(7);
        assertThat(inventoryRepository
                        .findByProductVariantIdAndWarehouseId(variantB.getId(), warehouse.getId())
                        .orElseThrow()
                        .getQuantity())
                .isZero();
    }

    @Test
    void shouldRollBackAllInventoryWhenOneOfMultipleVariantsUnavailableTest() {
        User user = createUserWithCart();
        ProductVariant variantA = createVariant(new BigDecimal("5.00"));
        ProductVariant variantB = createVariant(new BigDecimal("7.00"));
        Warehouse warehouse = createWarehouse();
        inventoryService.setInventory(variantA.getId(), warehouse.getId(), 10);
        inventoryService.setInventory(variantB.getId(), warehouse.getId(), 3);
        cartService.addItem(user.getId(), variantA.getId(), 2);
        cartService.addItem(user.getId(), variantB.getId(), 5);

        assertThatThrownBy(() -> orderService.createOrder(user.getId())).isInstanceOf(InsufficientStockException.class);

        assertThat(inventoryRepository
                        .findByProductVariantIdAndWarehouseId(variantA.getId(), warehouse.getId())
                        .orElseThrow()
                        .getQuantity())
                .as("variant A must not be decremented when variant B fails")
                .isEqualTo(10);
        assertThat(inventoryRepository
                        .findByProductVariantIdAndWarehouseId(variantB.getId(), warehouse.getId())
                        .orElseThrow()
                        .getQuantity())
                .isEqualTo(3);
        assertThat(findCartItems(user.getId())).hasSize(2);
    }

    @Test
    void shouldStoreExactInventoryAllocationOnOrderItemTest() {
        User user = createUserWithCart();
        ProductVariant variant = createVariant(new BigDecimal("9.99"));
        Warehouse warehouse = createWarehouse();
        Inventory inventory = inventoryService.setInventory(variant.getId(), warehouse.getId(), 5);
        cartService.addItem(user.getId(), variant.getId(), 2);

        Order order = orderService.createOrder(user.getId());

        List<OrderItem> items = orderItemRepository.findAllByOrderId(order.getId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getInventory().getId()).isEqualTo(inventory.getId());
    }

    @Test
    void shouldChooseSingleWarehouseCapableOfFullQuantityTest() {
        User user = createUserWithCart();
        ProductVariant variant = createVariant(new BigDecimal("9.99"));
        Warehouse warehouseA = createWarehouse();
        Warehouse warehouseB = createWarehouse();
        inventoryService.setInventory(variant.getId(), warehouseA.getId(), 2);
        inventoryService.setInventory(variant.getId(), warehouseB.getId(), 10);
        cartService.addItem(user.getId(), variant.getId(), 5);

        orderService.createOrder(user.getId());

        assertThat(inventoryRepository
                        .findByProductVariantIdAndWarehouseId(variant.getId(), warehouseA.getId())
                        .orElseThrow()
                        .getQuantity())
                .as("warehouse A must be untouched - it cannot fulfil the full quantity alone")
                .isEqualTo(2);
        assertThat(inventoryRepository
                        .findByProductVariantIdAndWarehouseId(variant.getId(), warehouseB.getId())
                        .orElseThrow()
                        .getQuantity())
                .isEqualTo(5);
    }

    @Test
    void shouldNotSplitFulfillmentAcrossWarehousesTest() {
        User user = createUserWithCart();
        ProductVariant variant = createVariant(new BigDecimal("9.99"));
        Warehouse warehouseA = createWarehouse();
        Warehouse warehouseB = createWarehouse();
        inventoryService.setInventory(variant.getId(), warehouseA.getId(), 2);
        inventoryService.setInventory(variant.getId(), warehouseB.getId(), 3);
        cartService.addItem(user.getId(), variant.getId(), 5);

        assertThatThrownBy(() -> orderService.createOrder(user.getId())).isInstanceOf(InsufficientStockException.class);

        assertThat(inventoryRepository
                        .findByProductVariantIdAndWarehouseId(variant.getId(), warehouseA.getId())
                        .orElseThrow()
                        .getQuantity())
                .isEqualTo(2);
        assertThat(inventoryRepository
                        .findByProductVariantIdAndWarehouseId(variant.getId(), warehouseB.getId())
                        .orElseThrow()
                        .getQuantity())
                .isEqualTo(3);
    }

    @Test
    void shouldRestoreStockToExactSourceOnEarlyCancellationTest() {
        User user = createUserWithCart();
        ProductVariant variant = createVariant(new BigDecimal("9.99"));
        Warehouse warehouse = createWarehouse();
        inventoryService.setInventory(variant.getId(), warehouse.getId(), 10);
        cartService.addItem(user.getId(), variant.getId(), 3);

        Order order = orderService.createOrder(user.getId());
        assertThat(inventoryRepository
                        .findByProductVariantIdAndWarehouseId(variant.getId(), warehouse.getId())
                        .orElseThrow()
                        .getQuantity())
                .isEqualTo(7);

        Order cancelled = orderService.updateStatus(order.getId(), OrderStatus.CANCELLED);

        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(inventoryRepository
                        .findByProductVariantIdAndWarehouseId(variant.getId(), warehouse.getId())
                        .orElseThrow()
                        .getQuantity())
                .isEqualTo(10);
    }

    @Test
    void shouldRestoreOnlyTheExactWarehouseNotOthersOnCancellationTest() {
        User user = createUserWithCart();
        ProductVariant variant = createVariant(new BigDecimal("9.99"));
        Warehouse warehouseA = createWarehouse();
        Warehouse warehouseB = createWarehouse();
        inventoryService.setInventory(variant.getId(), warehouseA.getId(), 2);
        inventoryService.setInventory(variant.getId(), warehouseB.getId(), 10);
        cartService.addItem(user.getId(), variant.getId(), 5);

        Order order = orderService.createOrder(user.getId());
        orderService.updateStatus(order.getId(), OrderStatus.CANCELLED);

        assertThat(inventoryRepository
                        .findByProductVariantIdAndWarehouseId(variant.getId(), warehouseA.getId())
                        .orElseThrow()
                        .getQuantity())
                .as("warehouse A was never touched, so cancellation must not credit it")
                .isEqualTo(2);
        assertThat(inventoryRepository
                        .findByProductVariantIdAndWarehouseId(variant.getId(), warehouseB.getId())
                        .orElseThrow()
                        .getQuantity())
                .isEqualTo(10);
    }

    @Test
    void shouldRestoreMultipleOrderItemsCorrectlyOnCancellationTest() {
        User user = createUserWithCart();
        ProductVariant variantA = createVariant(new BigDecimal("5.00"));
        ProductVariant variantB = createVariant(new BigDecimal("7.00"));
        Warehouse warehouse = createWarehouse();
        inventoryService.setInventory(variantA.getId(), warehouse.getId(), 10);
        inventoryService.setInventory(variantB.getId(), warehouse.getId(), 8);
        cartService.addItem(user.getId(), variantA.getId(), 3);
        cartService.addItem(user.getId(), variantB.getId(), 2);

        Order order = orderService.createOrder(user.getId());
        orderService.updateStatus(order.getId(), OrderStatus.CANCELLED);

        assertThat(inventoryRepository
                        .findByProductVariantIdAndWarehouseId(variantA.getId(), warehouse.getId())
                        .orElseThrow()
                        .getQuantity())
                .isEqualTo(10);
        assertThat(inventoryRepository
                        .findByProductVariantIdAndWarehouseId(variantB.getId(), warehouse.getId())
                        .orElseThrow()
                        .getQuantity())
                .isEqualTo(8);
    }

    @Test
    void shouldRejectDoubleCancellationAndNotDoubleRestockTest() {
        User user = createUserWithCart();
        ProductVariant variant = createVariant(new BigDecimal("9.99"));
        Warehouse warehouse = createWarehouse();
        inventoryService.setInventory(variant.getId(), warehouse.getId(), 10);
        cartService.addItem(user.getId(), variant.getId(), 3);

        Order order = orderService.createOrder(user.getId());
        orderService.updateStatus(order.getId(), OrderStatus.CANCELLED);

        assertThatThrownBy(() -> orderService.updateStatus(order.getId(), OrderStatus.CANCELLED))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(inventoryRepository
                        .findByProductVariantIdAndWarehouseId(variant.getId(), warehouse.getId())
                        .orElseThrow()
                        .getQuantity())
                .as("cancelling an already-cancelled order must not restock a second time")
                .isEqualTo(10);
    }

    @Test
    void shouldRejectCancellationAfterShippingAndLeaveStockAndStatusUnchangedTest() {
        User user = createUserWithCart();
        ProductVariant variant = createVariant(new BigDecimal("9.99"));
        Warehouse warehouse = createWarehouse();
        inventoryService.setInventory(variant.getId(), warehouse.getId(), 10);
        cartService.addItem(user.getId(), variant.getId(), 3);

        Order order = orderService.createOrder(user.getId());
        orderService.updateStatus(order.getId(), OrderStatus.PAID);
        orderService.updateStatus(order.getId(), OrderStatus.SHIPPING);

        assertThatThrownBy(() -> orderService.updateStatus(order.getId(), OrderStatus.CANCELLED))
                .isInstanceOf(IllegalArgumentException.class);

        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.SHIPPING);
        assertThat(inventoryRepository
                        .findByProductVariantIdAndWarehouseId(variant.getId(), warehouse.getId())
                        .orElseThrow()
                        .getQuantity())
                .isEqualTo(7);
    }

    @Test
    void shouldAllowExactlyOneOfTwoConcurrentCheckoutsWhenStockIsOneTest() throws InterruptedException {
        User userA = createUserWithCart();
        User userB = createUserWithCart();
        ProductVariant variant = createVariant(new BigDecimal("9.99"));
        Warehouse warehouse = createWarehouse();
        inventoryService.setInventory(variant.getId(), warehouse.getId(), 1);
        cartService.addItem(userA.getId(), variant.getId(), 1);
        cartService.addItem(userB.getId(), variant.getId(), 1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        try {
            var futureA = executor.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    orderService.createOrder(userA.getId());
                    successCount.incrementAndGet();
                } catch (InsufficientStockException e) {
                    failureCount.incrementAndGet();
                }
            });
            var futureB = executor.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    orderService.createOrder(userB.getId());
                    successCount.incrementAndGet();
                } catch (InsufficientStockException e) {
                    failureCount.incrementAndGet();
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

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(1);
        Inventory finalInventory = inventoryRepository
                .findByProductVariantIdAndWarehouseId(variant.getId(), warehouse.getId())
                .orElseThrow();
        assertThat(finalInventory.getQuantity()).isZero();
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
