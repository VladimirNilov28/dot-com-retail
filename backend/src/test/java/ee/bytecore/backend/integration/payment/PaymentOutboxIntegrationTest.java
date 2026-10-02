package ee.bytecore.backend.integration.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.entities.cart.Cart;
import ee.bytecore.backend.entities.inventory.Warehouse;
import ee.bytecore.backend.entities.payment.Order;
import ee.bytecore.backend.entities.payment.PaymentOutboxEvent;
import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.repositories.cart.CartRepository;
import ee.bytecore.backend.repositories.payment.PaymentOutboxEventRepository;
import ee.bytecore.backend.repositories.user.UserRepository;
import ee.bytecore.backend.services.CartService;
import ee.bytecore.backend.services.InventoryService;
import ee.bytecore.backend.services.OrderService;
import ee.bytecore.backend.services.ProductService;
import ee.bytecore.backend.services.ProductVariantService;
import ee.bytecore.backend.services.WarehouseService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies the transactional-outbox write side: {@code OrderService
 * .createOrder} must leave a {@code payment_outbox} row behind in the same
 * transaction as the Order it creates - no Kafka broker involved here at
 * all, matching {@link KafkaPaymentEventPublisher}, which never talks to
 * Kafka directly.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Tag("integration")
class PaymentOutboxIntegrationTest {

    @Autowired
    UserRepository userRepository;

    @Autowired
    CartRepository cartRepository;

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

    @Autowired
    PaymentOutboxEventRepository paymentOutboxEventRepository;

    @Autowired
    DataSource dataSource;

    private static final AtomicInteger COUNTER = new AtomicInteger();

    // Real commits are required here so this test shares its Spring context
    // (same PostgresTestConfiguration-only container) with the hardcoded-id
    // migration/schema tests without leaving permanent rows behind.
    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdOrderIds = new ArrayList<>();
    private final List<Long> createdProductIds = new ArrayList<>();
    private final List<Long> createdWarehouseIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        createdOrderIds.forEach(id -> jdbcTemplate.update("DELETE FROM payment_outbox WHERE order_id = ?", id));
        createdOrderIds.forEach(id -> jdbcTemplate.update("DELETE FROM orders WHERE id = ?", id));
        createdProductIds.forEach(id -> jdbcTemplate.update("DELETE FROM products WHERE id = ?", id));
        createdWarehouseIds.forEach(id -> jdbcTemplate.update("DELETE FROM warehouses WHERE id = ?", id));
        createdUserIds.forEach(id -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", id));
    }

    private User createUserWithCart() {
        int n = COUNTER.incrementAndGet();
        User user = userRepository.save(
                User.create("outbox-test-user-" + n, "outbox-test-" + n + "@example.com", LocalDate.of(1990, 1, 1)));
        cartRepository.save(Cart.create(user));
        createdUserIds.add(user.getId());
        return user;
    }

    private ProductVariant createVariant(BigDecimal price) {
        int n = COUNTER.incrementAndGet();
        Product product = productService.create("Outbox Test Product " + n, "outbox-test-product-" + n, null, null);
        createdProductIds.add(product.getId());
        return productVariantService.create(product.getId(), "OUTBOX-TEST-SKU-" + n, price, null, null, null);
    }

    private Warehouse createWarehouse() {
        int n = COUNTER.incrementAndGet();
        Warehouse warehouse = warehouseService.create("Outbox Test Warehouse " + n, "Somewhere " + n);
        createdWarehouseIds.add(warehouse.getId());
        return warehouse;
    }

    @Test
    void createOrderWritesUnpublishedPaymentRequestedOutboxRowInSameTransactionTest() {
        User user = createUserWithCart();
        ProductVariant variant = createVariant(new BigDecimal("19.99"));
        Warehouse warehouse = createWarehouse();
        inventoryService.setInventory(variant.getId(), warehouse.getId(), 5);
        cartService.addItem(user.getId(), variant.getId(), 2);

        Order order = orderService.createOrder(user.getId());
        createdOrderIds.add(order.getId());

        List<PaymentOutboxEvent> outboxRows = paymentOutboxEventRepository.findAllByPublishedFalseOrderByCreatedAtAsc();
        PaymentOutboxEvent event = outboxRows.stream()
                .filter(row -> row.getOrderId().equals(order.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected an outbox row for order " + order.getId()));

        assertThat(event.getEventType()).isEqualTo(PaymentTopics.PAYMENT_REQUESTED);
        assertThat(event.isPublished()).isFalse();
        assertThat(event.getPayload())
                .contains("\"orderId\": " + order.getId())
                .contains("\"userId\": " + user.getId())
                .contains("\"amount\": 39.98");
    }
}
