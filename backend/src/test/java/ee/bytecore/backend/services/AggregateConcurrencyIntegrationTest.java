package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ee.bytecore.backend.config.KafkaTestConfiguration;
import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.entities.cart.CartItem;
import ee.bytecore.backend.entities.category.Category;
import ee.bytecore.backend.entities.inventory.Inventory;
import ee.bytecore.backend.entities.payment.Order;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.enums.OrderStatus;
import ee.bytecore.backend.graphql.mappers.CategoryMapper;
import ee.bytecore.backend.integration.payment.PaymentOutboxPublisher;
import ee.bytecore.backend.integration.payment.PaymentResultListener;
import ee.bytecore.backend.integration.payment.event.PaymentFailedEvent;
import ee.bytecore.backend.repositories.cart.CartItemRepository;
import ee.bytecore.backend.repositories.cart.CartRepository;
import ee.bytecore.backend.repositories.category.CategoryRepository;
import ee.bytecore.backend.repositories.inventory.InventoryRepository;
import ee.bytecore.backend.repositories.payment.OrderItemRepository;
import ee.bytecore.backend.repositories.payment.OrderRepository;
import ee.bytecore.backend.repositories.user.UserRepository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@SpringBootTest
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class})
@Tag("integration")
@Timeout(45)
class AggregateConcurrencyIntegrationTest {
    @MockitoBean
    PaymentOutboxPublisher outboxPublisher;

    @Autowired
    DataSource dataSource;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    UserRepository users;

    @Autowired
    CartRepository carts;

    @Autowired
    CartItemRepository cartItems;

    @Autowired
    CategoryRepository categories;

    @Autowired
    InventoryRepository inventories;

    @Autowired
    OrderRepository orders;

    @Autowired
    OrderItemRepository orderItems;

    @Autowired
    CartService cartService;

    @Autowired
    CategoryService categoryService;

    @Autowired
    OrderService orderService;

    @Autowired
    InventoryService inventoryService;

    @Autowired
    ProductService productService;

    @Autowired
    ProductVariantService variantService;

    @Autowired
    WarehouseService warehouseService;

    @Autowired
    PaymentResultListener paymentResults;

    @Autowired
    OrderStatusPublisher statusPublisher;

    private record Fixture(User user, ProductVariant variant, Inventory inventory, CartItem item) {}

    private Fixture fixture() {
        String key = UUID.randomUUID().toString();
        User user = users.save(User.create(key, key + "@example.com", LocalDate.of(1990, 1, 1)));
        var product = productService.create(key, key, null, null);
        var variant = variantService.create(product.getId(), key, BigDecimal.TEN, null, null, null);
        var warehouse = warehouseService.create(key, key);
        Inventory inventory = inventoryService.setInventory(variant.getId(), warehouse.getId(), 10);
        CartItem item = cartService.addItem(user.getId(), variant.getId(), 1);
        return new Fixture(user, variant, inventory, item);
    }

    /**
     * A real database lock gates the write, not a mocked repository/proxy. Workers
     * are identified by transaction-local application_name; PostgreSQL's wait
     * graph proves they reached the controlled interleaving before we release it.
     */
    private final class Interleaving implements AutoCloseable {
        private final Connection gate;
        private final ExecutorService executor = Executors.newFixedThreadPool(2);
        private final List<Future<Object>> futures = new ArrayList<>();
        private final String name = "race-" + UUID.randomUUID();
        private boolean listenerCalls;

        Interleaving(String table, Long id) throws Exception {
            gate = dataSource.getConnection();
            gate.setAutoCommit(false);
            try (var statement = gate.prepareStatement("SELECT id FROM " + table + " WHERE id = ? FOR UPDATE")) {
                statement.setLong(1, id);
                statement.executeQuery().close();
            }
        }

        void start(Callable<?> operation) {
            futures.add(executor.submit(() -> {
                try {
                    return new TransactionTemplate(transactionManager).execute(status -> {
                        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
                        jdbc.queryForObject("SELECT set_config('application_name', ?, true)", String.class, name);
                        jdbc.execute("SET LOCAL lock_timeout = '20s'");
                        try {
                            return operation.call();
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });
                } catch (RuntimeException e) {
                    return e;
                }
            }));
        }

        void startListener(Runnable operation) {
            listenerCalls = true;
            futures.add(executor.submit(() -> {
                operation.run();
                return true;
            }));
        }

        void reached(int count) {
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                JdbcTemplate jdbc = new JdbcTemplate(dataSource);
                long blocked = listenerCalls
                        ? jdbc.queryForObject(
                                "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                                        + "AND cardinality(pg_blocking_pids(pid)) > 0",
                                Long.class)
                        : jdbc.queryForObject(
                                "SELECT count(*) FROM pg_stat_activity WHERE application_name = ? "
                                        + "AND cardinality(pg_blocking_pids(pid)) > 0",
                                Long.class,
                                name);
                return blocked + futures.stream().filter(Future::isDone).count() >= count;
            });
        }

        List<Object> finish() throws Exception {
            gate.commit();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        }

        @Override
        public void close() throws Exception {
            gate.rollback();
            gate.close();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentCancellationsRestockExactlyOnce() throws Exception {
        Fixture f = fixture();
        var otherWarehouse = warehouseService.create(UUID.randomUUID().toString(), "other");
        Inventory other = inventoryService.setInventory(f.variant.getId(), otherWarehouse.getId(), 2);
        Order order = orderService.createOrder(f.user.getId());
        try (var race = new Interleaving("inventory", f.inventory.getId())) {
            race.start(() -> orderService.updateStatus(order.getId(), OrderStatus.CANCELLED));
            race.start(() -> orderService.updateStatus(order.getId(), OrderStatus.CANCELLED));
            race.reached(2);
            assertThat(race.finish().stream()
                            .filter(IllegalArgumentException.class::isInstance)
                            .count())
                    .isEqualTo(1);
        }
        assertThat(inventories.findById(f.inventory.getId()).orElseThrow().getQuantity())
                .isEqualTo(10);
        assertThat(inventories.findById(other.getId()).orElseThrow().getQuantity())
                .isEqualTo(2);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancellationCannotBeOverwrittenByPayment() throws Exception {
        assertCancellationWins(OrderStatus.PENDING, OrderStatus.PAID);
    }

    @Test
    void cancellationCannotBeOverwrittenByStaffShipping() throws Exception {
        assertCancellationWins(OrderStatus.PAID, OrderStatus.SHIPPING);
    }

    private void assertCancellationWins(OrderStatus initial, OrderStatus competing) throws Exception {
        Fixture f = fixture();
        Order order = orderService.createOrder(f.user.getId());
        if (initial == OrderStatus.PAID) orderService.updateStatus(order.getId(), initial);
        try (var race = new Interleaving("inventory", f.inventory.getId())) {
            race.start(() -> orderService.updateStatus(order.getId(), OrderStatus.CANCELLED));
            race.reached(1);
            race.start(() -> orderService.updateStatus(order.getId(), competing));
            race.reached(2);
            assertThat(race.finish().get(1)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(inventories.findById(f.inventory.getId()).orElseThrow().getQuantity())
                .isEqualTo(10);
    }

    @Test
    void concurrentDuplicatePaymentFailuresRestockOnce() throws Exception {
        Fixture f = fixture();
        Order order = orderService.createOrder(f.user.getId());
        String payload = new ObjectMapper()
                .findAndRegisterModules()
                .writeValueAsString(new PaymentFailedEvent(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        order.getId(),
                        UUID.randomUUID(),
                        "declined",
                        Instant.now()));
        try (var race = new Interleaving("inventory", f.inventory.getId())) {
            for (int i = 0; i < 2; i++) race.startListener(() -> paymentResults.onPaymentFailed(payload));
            race.reached(2);
            assertThat(race.finish()).containsExactly(true, true);
        }
        paymentResults.onPaymentFailed(payload);
        assertThat(inventories.findById(f.inventory.getId()).orElseThrow().getQuantity())
                .isEqualTo(10);
        assertThat(orders.findById(order.getId()).orElseThrow().getCancellationReason())
                .contains("declined");
    }

    @Test
    void cancellationRollbackKeepsStateAndStockTogether() {
        Fixture f = fixture();
        Order order = orderService.createOrder(f.user.getId());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            orderService.updateStatus(order.getId(), OrderStatus.CANCELLED);
            status.setRollbackOnly();
        });
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(inventories.findById(f.inventory.getId()).orElseThrow().getQuantity())
                .isEqualTo(9);
        orderService.updateStatus(order.getId(), OrderStatus.PAID);
        orderService.updateStatus(order.getId(), OrderStatus.CANCELLED);
        assertThat(inventories.findById(f.inventory.getId()).orElseThrow().getQuantity())
                .isEqualTo(10);
    }

    @Test
    void rolledBackCancellationDoesNotPublishStatus() {
        Fixture f = fixture();
        Order order = orderService.createOrder(f.user.getId());
        List<OrderStatus> published = new ArrayList<>();
        var subscription =
                statusPublisher.subscribeTo(order.getId()).subscribe(changed -> published.add(changed.getStatus()));
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                orderService.updateStatus(order.getId(), OrderStatus.CANCELLED);
                status.setRollbackOnly();
            });
            assertThat(published).isEmpty();
            orderService.updateStatus(order.getId(), OrderStatus.CANCELLED);
            assertThat(published).containsExactly(OrderStatus.CANCELLED);
        } finally {
            subscription.dispose();
        }
    }

    @Test
    void concurrentCartIncrementsAreNotLost() throws Exception {
        Fixture f = fixture();
        try (var race = new Interleaving("cart_items", f.item.getId())) {
            race.start(() -> cartService.addItem(f.user.getId(), f.variant.getId(), 1));
            race.start(() -> cartService.addItem(f.user.getId(), f.variant.getId(), 1));
            race.reached(2);
            assertThat(race.finish()).allMatch(CartItem.class::isInstance);
        }
        assertThat(cartItems.findById(f.item.getId()).orElseThrow().getQuantity())
                .isEqualTo(3);
    }

    @Test
    void concurrentNewCartItemsMergeIntoOneMembership() throws Exception {
        Fixture f = fixture();
        cartService.removeItem(f.user.getId(), f.item.getId());
        Long cartId = carts.findByUserId(f.user.getId()).orElseThrow().getId();
        try (var race = new Interleaving("carts", cartId)) {
            race.start(() -> cartService.addItem(f.user.getId(), f.variant.getId(), 1));
            race.start(() -> cartService.addItem(f.user.getId(), f.variant.getId(), 1));
            race.reached(2);
            assertThat(race.finish()).allMatch(CartItem.class::isInstance);
        }
        assertThat(cartItems.findAllByCartId(cartId)).singleElement().satisfies(i -> assertThat(i.getQuantity())
                .isEqualTo(2));
    }

    @Test
    void additionOverlappingCheckoutRemainsInCart() throws Exception {
        Fixture f = fixture();
        Order order;
        try (var race = new Interleaving("inventory", f.inventory.getId())) {
            race.start(() -> orderService.createOrder(f.user.getId()));
            race.reached(1);
            race.start(() -> cartService.addItem(f.user.getId(), f.variant.getId(), 1));
            race.reached(2);
            List<Object> results = race.finish();
            assertThat(results.get(1)).isInstanceOf(CartItem.class);
            order = (Order) results.get(0);
        }
        assertThat(orderItems.findAllByOrderId(order.getId()))
                .singleElement()
                .satisfies(i -> assertThat(i.getQuantity()).isEqualTo(1));
        assertThat(cartItems.findAllByCartId(f.item.getCart().getId()))
                .singleElement()
                .satisfies(i -> assertThat(i.getQuantity()).isEqualTo(1));
    }

    @Test
    void everyCartMutationWaitsForParentAggregateLock() throws Exception {
        for (String operation : List.of("update", "remove", "clear")) {
            Fixture f = fixture();
            try (var race = new Interleaving("carts", f.item.getCart().getId())) {
                race.start(() -> switch (operation) {
                    case "update" -> cartService.updateItemQuantity(f.user.getId(), f.item.getId(), 2);
                    case "remove" -> cartService.removeItem(f.user.getId(), f.item.getId());
                    default -> cartService.clear(f.user.getId());
                });
                race.reached(1);
                assertThat(race.futures.getFirst().isDone())
                        .as(operation + " must wait for cart lock")
                        .isFalse();
                assertThat(race.finish()).noneMatch(RuntimeException.class::isInstance);
            }
        }
    }

    @Test
    void concurrentCheckoutsConsumeSameCartOnlyOnce() throws Exception {
        Fixture f = fixture();
        try (var race = new Interleaving("inventory", f.inventory.getId())) {
            race.start(() -> orderService.createOrder(f.user.getId()));
            race.reached(1);
            race.start(() -> orderService.createOrder(f.user.getId()));
            race.reached(2);
            assertThat(race.finish().get(1)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(orders.findAllByUserId(f.user.getId())).hasSize(1);
        assertThat(inventories.findById(f.inventory.getId()).orElseThrow().getQuantity())
                .isEqualTo(9);
    }

    @Test
    void rejectsSelfAndDescendantCategoryParentsBeforeCommit() {
        Category a = categoryService.create("a", UUID.randomUUID().toString(), null);
        Category b = categoryService.create("b", UUID.randomUUID().toString(), a.getId());
        Category c = categoryService.create("c", UUID.randomUUID().toString(), b.getId());
        for (Long parent : List.of(a.getId(), b.getId(), c.getId())) {
            assertThatThrownBy(() -> categoryService.update(a.getId(), "changed", null, parent))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cycle");
            assertThat(categories.findById(a.getId()).orElseThrow().getName()).isEqualTo("a");
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var mapped =
                    CategoryMapper.toGraphQlType(categories.findById(c.getId()).orElseThrow());
            assertThat(mapped.getParent().getParent().getId())
                    .isEqualTo(a.getId().toString());
        });
    }

    @Test
    void concurrentCategoryReparentingCannotCreateCycle() throws Exception {
        Category a = categoryService.create("a", UUID.randomUUID().toString(), null);
        Category b = categoryService.create("b", UUID.randomUUID().toString(), null);
        try (var race = new Interleaving("categories", a.getId())) {
            race.start(() -> categoryService.update(a.getId(), null, null, b.getId()));
            race.reached(1);
            race.start(() -> categoryService.update(b.getId(), null, null, a.getId()));
            race.reached(2);
            assertThat(race.finish().stream()
                            .filter(IllegalArgumentException.class::isInstance)
                            .count())
                    .isEqualTo(1);
        }
    }

    @Test
    void persistedCategoryCycleFailsWithActionableErrorAndIsNotRepaired() {
        Category a = categoryService.create("a", UUID.randomUUID().toString(), null);
        Category b = categoryService.create("b", UUID.randomUUID().toString(), a.getId());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE categories SET parent_id = ? WHERE id = ?", b.getId(), a.getId());
        try {
            assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                            .execute(status -> CategoryMapper.toGraphQlType(
                                    categories.findById(a.getId()).orElseThrow())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cycle");
            assertThatThrownBy(() -> categoryService.update(b.getId(), null, null, a.getId()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cycle");
            assertThat(jdbc.queryForObject("SELECT parent_id FROM categories WHERE id = ?", Long.class, a.getId()))
                    .isEqualTo(b.getId());
        } finally {
            jdbc.update("UPDATE categories SET parent_id = NULL WHERE id = ?", a.getId());
        }
    }
}
