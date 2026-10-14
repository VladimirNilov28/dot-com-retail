package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.entities.payment.Order;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.enums.OrderStatus;
import ee.bytecore.backend.exceptions.CheckoutException;
import ee.bytecore.backend.integration.payment.PaymentOutboxPublisher;
import ee.bytecore.backend.integration.payment.PaymentResultListener;
import ee.bytecore.backend.integration.payment.RefundResultListener;
import ee.bytecore.backend.integration.payment.StripePaymentClient;
import ee.bytecore.backend.integration.payment.event.PaymentRequestedEvent;
import ee.bytecore.backend.integration.payment.event.PaymentSucceededEvent;
import ee.bytecore.backend.integration.payment.event.RefundRequestedEvent;
import ee.bytecore.backend.integration.payment.event.RefundResultEvent;
import ee.bytecore.backend.repositories.user.UserRepository;
import ee.bytecore.backend.services.CheckoutValues.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Order lifecycle after payment: fulfillment chain, tracking, history, refund requests and paging. */
@SpringBootTest(
        properties = {
            "spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.listener.auto-startup=false",
            "spring.docker.compose.enabled=false", "order.mail-poll-ms=999999999",
            "management.health.mail.enabled=false"
        })
@Import(PostgresTestConfiguration.class)
@Tag("integration")
class OrderLifecycleIntegrationTest {
    @Autowired
    OrderService orders;

    @Autowired
    CartService carts;

    @Autowired
    ProductService products;

    @Autowired
    ProductVariantService variants;

    @Autowired
    InventoryService inventory;

    @Autowired
    WarehouseService warehouses;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PaymentResultListener results;

    @Autowired
    RefundResultListener refundResults;

    @MockitoBean
    PaymentOutboxPublisher publisher;

    @MockitoBean
    StripePaymentClient stripe;

    @MockitoBean
    JavaMailSender mail;

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    record Fixture(User user, ProductVariant variant, User staff) {}

    private Fixture fixture() {
        String key = UUID.randomUUID().toString();
        var user = users.save(User.create(key, key + "@example.test", LocalDate.of(1990, 1, 1)));
        var staff = users.save(User.create("s" + key, "s" + key + "@example.test", LocalDate.of(1990, 1, 1)));
        var product = products.create("Lifecycle fixture " + key, key, null, null);
        var variant = variants.create(product.getId(), key, new BigDecimal("19.99"), Map.of("size", "M"), null, null);
        var warehouse = warehouses.create(key, "Disposable fixture");
        inventory.setInventory(variant.getId(), warehouse.getId(), 6);
        carts.addItem(user.getId(), variant.getId(), 1);
        return new Fixture(user, variant, staff);
    }

    private Order paidOrder(Fixture fixture) throws Exception {
        var selection = new Selection(
                fixture.user().getEmail(),
                null,
                "STANDARD",
                null,
                new Address("Ada", "Example", "Tallinn", "EE", "10111", "Fixture 1", null, null),
                null,
                null,
                true,
                "STRIPE",
                null);
        var placement = new Placement(
                UUID.randomUUID(),
                orders.previewCheckout(fixture.user().getId(), null, selection).quoteVersion(),
                selection);
        var order = orders.placeCheckout(fixture.user().getId(), null, null, placement);
        var request = JSON.readValue(
                jdbc.queryForObject(
                        "SELECT payload::text FROM payment_outbox WHERE order_id=? AND event_type='payment.requested'",
                        String.class,
                        order.getId()),
                PaymentRequestedEvent.class);
        results.onPaymentSucceeded(JSON.writeValueAsString(new PaymentSucceededEvent(
                UUID.randomUUID(), request.eventId(), order.getId(), UUID.randomUUID(), Instant.now(), "pi_fixture")));
        return orders.findById(order.getId()).orElseThrow();
    }

    private Integer refundRequestedEvents(Long orderId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payment_outbox WHERE order_id=? AND event_type='refund.requested'",
                Integer.class,
                orderId);
    }

    @BeforeEach
    void sandbox() {
        when(stripe.configuration()).thenReturn(new StripePaymentClient.Configuration(true, "pk_test_fixture"));
        when(mail.createMimeMessage()).thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    void fulfillmentFollowsTheChainBlocksCancellationAndRecordsHistory() throws Exception {
        var f = fixture();
        var order = paidOrder(f);
        Long staff = f.staff().getId();

        assertThatThrownBy(() -> orders.updateFulfillmentStatus(order.getId(), OrderStatus.SHIPPING, staff))
                .isInstanceOf(IllegalArgumentException.class);
        orders.updateFulfillmentStatus(order.getId(), OrderStatus.PROCESSING, staff, "Picking");
        assertThatThrownBy(() -> orders.cancelOrder(
                        UUID.randomUUID(), order.getPublicId(), f.user().getId(), null, false))
                .isInstanceOf(CheckoutException.class)
                .hasMessageContaining("PROCESSING_BEGUN");
        assertThatThrownBy(() -> orders.cancelOrder(UUID.randomUUID(), order.getPublicId(), staff, null, true))
                .isInstanceOf(CheckoutException.class);
        assertThatThrownBy(() -> orders.updateFulfillmentStatus(order.getId(), OrderStatus.SHIPPING, staff))
                .isInstanceOf(CheckoutException.class)
                .hasMessageContaining("tracking");

        assertThatThrownBy(() -> orders.setTracking(order.getPublicId(), "DHL", "12", null, staff))
                .isInstanceOf(CheckoutException.class);
        assertThatThrownBy(() -> orders.setTracking(order.getPublicId(), "DHL", "JD0123", "javascript:alert(1)", staff))
                .isInstanceOf(CheckoutException.class);
        assertThatThrownBy(() ->
                        orders.setTracking(order.getPublicId(), "DHL", "JD0123", "https://user@track.example/x", staff))
                .isInstanceOf(CheckoutException.class);
        orders.setTracking(order.getPublicId(), "DHL Express", "JD0123", "https://track.example/JD0123", staff);
        orders.updateFulfillmentStatus(order.getId(), OrderStatus.SHIPPING, staff);
        orders.updateFulfillmentStatus(order.getId(), OrderStatus.COMPLETED, staff);

        var reloaded = orders.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(reloaded.getShippedAt()).isNotNull();
        assertThat(inventory.sellableQuantity(f.variant().getId())).isEqualTo(5);
        assertThat(orders.statusHistory(order.getId()))
                .extracting(e -> e.getToStatus() + ":" + e.getActorKind())
                .containsExactly(
                        "PENDING:CUSTOMER", "PAID:PAYMENT", "PROCESSING:STAFF", "SHIPPING:STAFF", "COMPLETED:STAFF");
        assertThat(orders.statusHistory(order.getId()).get(2).getNote()).isEqualTo("Picking");
        assertThat(jdbc.queryForList(
                        "SELECT kind FROM order_notifications WHERE order_id=?", String.class, order.getId()))
                .containsExactlyInAnyOrder("PLACED", "PAID", "PROCESSING", "SHIPPED", "COMPLETED");
        assertThat(jdbc.queryForObject(
                        "SELECT body FROM order_notifications WHERE order_id=? AND kind='SHIPPED'",
                        String.class,
                        order.getId()))
                .contains("DHL Express", "JD0123", "https://track.example/JD0123");
        assertThat(jdbc.queryForList(
                        "SELECT operation FROM admin_audit_log WHERE target_id=?",
                        String.class,
                        order.getPublicId().toString()))
                .contains("ORDER_STATUS_UPDATED", "ORDER_TRACKING_SET");
    }

    @Test
    void refundRequestIsOwnedIdempotentAndApprovalRefundsWithoutRestocking() throws Exception {
        var f = fixture();
        var foreign = fixture();
        var order = paidOrder(f);
        Long staff = f.staff().getId();
        orders.updateFulfillmentStatus(order.getId(), OrderStatus.PROCESSING, staff);

        assertThatThrownBy(() -> orders.requestRefund(
                        order.getPublicId(), "Not mine", foreign.user().getId()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> orders.requestRefund(order.getPublicId(), "Guest", null))
                .isInstanceOf(AccessDeniedException.class);
        var first = orders.requestRefund(
                order.getPublicId(), "  Changed my mind  ", f.user().getId());
        var replay = orders.requestRefund(
                order.getPublicId(), "Another reason", f.user().getId());
        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(first.getReason()).isEqualTo("Changed my mind");
        assertThat(orders.refundEligibility(order).reason()).isEqualTo("REQUEST_PENDING");

        var approved = orders.approveRefund(first.getId(), "Goodwill", staff);
        assertThat(approved.getStatus()).isEqualTo("APPROVED");
        assertThat(orders.approveRefund(first.getId(), null, staff).getRefundId())
                .isEqualTo(approved.getRefundId());
        assertThat(refundRequestedEvents(order.getId())).isEqualTo(1);
        assertThat(inventory.sellableQuantity(f.variant().getId())).isEqualTo(5);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PROCESSING);
        assertThat(orders.refundEligibility(order).reason()).isEqualTo("ALREADY_REFUNDED");
        assertThatThrownBy(() -> orders.requestRefund(
                        order.getPublicId(), "Again please", f.user().getId()))
                .isInstanceOf(CheckoutException.class);

        var refundEvent = JSON.readValue(
                jdbc.queryForObject(
                        "SELECT payload::text FROM payment_outbox WHERE order_id=? AND event_type='refund.requested'",
                        String.class,
                        order.getId()),
                RefundRequestedEvent.class);
        String result = JSON.writeValueAsString(new RefundResultEvent(
                UUID.randomUUID(),
                refundEvent.eventId(),
                refundEvent.refundId(),
                order.getId(),
                refundEvent.paymentId(),
                "re_fixture",
                null,
                Instant.now()));
        refundResults.onRefundSucceeded(result);
        refundResults.onRefundSucceeded(result);
        assertThat(orders.recovery(order.getId(), f.user().getId(), false)
                        .refund()
                        .status())
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForList(
                        "SELECT kind FROM order_notifications WHERE order_id=? AND kind LIKE 'REFUND%'",
                        String.class, order.getId()))
                .containsExactlyInAnyOrder("REFUND_REQUESTED", "REFUNDED");
        assertThat(jdbc.queryForList(
                        "SELECT operation FROM admin_audit_log WHERE target_id=?",
                        String.class,
                        order.getPublicId().toString()))
                .contains("ORDER_REFUND_APPROVED");
    }

    @Test
    void rejectedRequestCanBeResubmittedAndCancellationClosesAnOpenRequest() throws Exception {
        var f = fixture();
        var order = paidOrder(f);
        Long staff = f.staff().getId();
        assertThatThrownBy(() ->
                        orders.requestRefund(order.getPublicId(), "x", f.user().getId()))
                .isInstanceOf(IllegalArgumentException.class);
        var first =
                orders.requestRefund(order.getPublicId(), "Wrong size", f.user().getId());
        assertThatThrownBy(() -> orders.rejectRefund(first.getId(), null, staff))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(orders.rejectRefund(first.getId(), "Outside policy", staff).getStatus())
                .isEqualTo("REJECTED");
        assertThatThrownBy(() -> orders.approveRefund(first.getId(), null, staff))
                .isInstanceOf(CheckoutException.class);
        var second = orders.requestRefund(
                order.getPublicId(), "Still wrong size", f.user().getId());
        assertThat(second.getId()).isNotEqualTo(first.getId());

        orders.cancelOrder(UUID.randomUUID(), order.getPublicId(), f.user().getId(), null, false);
        assertThat(orders.refundRequests(order.getId()))
                .extracting(r -> r.getStatus())
                .containsExactly("REJECTED", "APPROVED");
        assertThat(refundRequestedEvents(order.getId())).isEqualTo(1);
        assertThat(inventory.sellableQuantity(f.variant().getId())).isEqualTo(6);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM order_notifications WHERE order_id=? AND kind='REFUND_REQUESTED'",
                        Integer.class,
                        order.getId()))
                .isEqualTo(2);
    }

    @Test
    void ordersArePagedFilteredAndBounded() throws Exception {
        var f = fixture();
        var paid = paidOrder(f);
        carts.addItem(f.user().getId(), f.variant().getId(), 1);
        var pending = orders.createOrder(f.user().getId());
        var foreign = fixture();
        orders.createOrder(foreign.user().getId());

        var all = orders.myOrders(f.user().getId(), null, 0, 1);
        assertThat(all.totalCount()).isEqualTo(2);
        assertThat(all.items()).extracting(Order::getId).containsExactly(pending.getId());
        assertThat(orders.myOrders(f.user().getId(), null, 1, 1).items())
                .extracting(Order::getId)
                .containsExactly(paid.getId());
        assertThat(orders.myOrders(
                                f.user().getId(),
                                new OrderService.OrderFilter(OrderStatus.PAID, null, null, null),
                                0,
                                20)
                        .items())
                .extracting(Order::getId)
                .containsExactly(paid.getId());
        Instant future = Instant.now().plus(1, ChronoUnit.DAYS);
        assertThat(orders.myOrders(f.user().getId(), new OrderService.OrderFilter(null, future, null, null), 0, 20)
                        .totalCount())
                .isZero();
        assertThatThrownBy(() -> orders.myOrders(f.user().getId(), null, 0, 51))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orders.myOrders(f.user().getId(), null, -1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orders.myOrders(
                        f.user().getId(), new OrderService.OrderFilter(null, future, Instant.now(), null), 0, 10))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(orders.staffOrders(
                                new OrderService.OrderFilter(
                                        null, null, null, f.user().getEmail()),
                                0,
                                50)
                        .totalCount())
                .isEqualTo(2);
        assertThat(orders.staffOrders(
                                new OrderService.OrderFilter(
                                        null,
                                        null,
                                        null,
                                        paid.getPublicId().toString().substring(0, 8)),
                                0,
                                50)
                        .items())
                .extracting(Order::getId)
                .contains(paid.getId());
    }

    @Test
    void notificationEnqueueIsDedupedPerKindAndReference() throws Exception {
        var f = fixture();
        var order = paidOrder(f);
        var notifications = new ee.bytecore.backend.integration.payment.OrderNotifications(
                jdbc, mail, mock(org.springframework.transaction.PlatformTransactionManager.class), "x@example.test");
        notifications.enqueue(order, "PAID");
        notifications.enqueue(order, "REFUND_REJECTED", "a", "note");
        notifications.enqueue(order, "REFUND_REJECTED", "a", "note");
        notifications.enqueue(order, "REFUND_REJECTED", "b", "note");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM order_notifications WHERE order_id=? AND kind='PAID'",
                        Integer.class,
                        order.getId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM order_notifications WHERE order_id=? AND kind='REFUND_REJECTED'",
                        Integer.class,
                        order.getId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT recipient FROM order_notifications WHERE order_id=? AND kind='PAID'",
                        String.class,
                        order.getId()))
                .isEqualTo(f.user().getEmail());
    }
}
