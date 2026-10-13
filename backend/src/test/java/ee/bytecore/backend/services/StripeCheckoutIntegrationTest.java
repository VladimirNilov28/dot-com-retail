package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.integration.payment.OrderNotifications;
import ee.bytecore.backend.integration.payment.PaymentOutboxPublisher;
import ee.bytecore.backend.integration.payment.PaymentResultListener;
import ee.bytecore.backend.integration.payment.StripePaymentClient;
import ee.bytecore.backend.integration.payment.event.PaymentRequestedEvent;
import ee.bytecore.backend.integration.payment.event.PaymentSucceededEvent;
import ee.bytecore.backend.integration.payment.event.PaymentUnresolvedEvent;
import ee.bytecore.backend.repositories.user.UserRepository;
import ee.bytecore.backend.security.GuestCartCredentials;
import ee.bytecore.backend.services.CheckoutValues.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@SpringBootTest(
        properties = {
            "spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.listener.auto-startup=false",
            "spring.docker.compose.enabled=false", "order.mail-poll-ms=999999999",
            "management.health.mail.enabled=false"
        })
@Import(PostgresTestConfiguration.class)
@Tag("integration")
class StripeCheckoutIntegrationTest {
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
    OrderNotifications notifications;

    @Autowired
    PlatformTransactionManager transactions;

    @MockitoBean
    PaymentOutboxPublisher publisher;

    @MockitoBean
    StripePaymentClient stripe;

    @MockitoBean
    JavaMailSender mail;

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    record Fixture(User user, ProductVariant variant) {}

    private Fixture fixture() {
        String key = UUID.randomUUID().toString();
        var user = users.save(User.create(key, key + "@example.test", LocalDate.of(1990, 1, 1)));
        var product = products.create("Stripe fixture " + key, key, null, null);
        var variant = variants.create(product.getId(), key, new BigDecimal("19.99"), Map.of("size", "M"), null, null);
        var warehouse = warehouses.create(key, "Disposable fixture");
        inventory.setInventory(variant.getId(), warehouse.getId(), 6);
        carts.addItem(user.getId(), variant.getId(), 1);
        return new Fixture(user, variant);
    }

    private Selection selection() {
        return new Selection(
                "stripe@example.test",
                null,
                "STANDARD",
                null,
                new Address("Ada", "Example", "Tallinn", "EE", "10111", "Fixture 1", null, null),
                null,
                null,
                true,
                "STRIPE",
                null);
    }

    private Placement placement(Fixture fixture) {
        var selection = selection();
        return new Placement(
                UUID.randomUUID(),
                orders.previewCheckout(fixture.user().getId(), null, selection).quoteVersion(),
                selection);
    }

    private PaymentRequestedEvent request(Long orderId) throws Exception {
        return JSON.readValue(
                jdbc.queryForObject(
                        "SELECT payload::text FROM payment_outbox WHERE order_id=? AND event_type='payment.requested'",
                        String.class,
                        orderId),
                PaymentRequestedEvent.class);
    }

    @BeforeEach
    void sandbox() {
        when(stripe.configuration()).thenReturn(new StripePaymentClient.Configuration(true, "pk_test_fixture"));
        when(stripe.session(any()))
                .thenReturn(new StripePaymentClient.Session(
                        "requires_payment_method", "pk_test_fixture", "pi_fixture_secret_fixture", null));
        when(mail.createMimeMessage()).thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    void placementReplayAndRetryableDeclineRetainOneOrderAndAllocation() throws Exception {
        var fixture = fixture();
        var input = placement(fixture);
        var order = orders.placeCheckout(fixture.user().getId(), null, null, input);
        var event = request(order.getId());
        assertThat(event.provider()).isEqualTo("stripe");
        assertThat(event.amount()).isEqualByComparingTo("24.98");
        assertThat(orders.placeCheckout(fixture.user().getId(), null, null, input)
                        .getId())
                .isEqualTo(order.getId());
        UUID payment = UUID.randomUUID();
        results.onPaymentUnresolved(JSON.writeValueAsString(new PaymentUnresolvedEvent(
                UUID.randomUUID(), event.eventId(), order.getId(), payment, "payment_retry_required", Instant.now())));
        assertThat(orders.checkoutOrder(input.requestId(), fixture.user().getId())
                        .status())
                .isEqualTo("PENDING");
        assertThat(inventory.sellableQuantity(fixture.variant().getId())).isEqualTo(5);
        var success = JSON.writeValueAsString(new PaymentSucceededEvent(
                UUID.randomUUID(), event.eventId(), order.getId(), payment, Instant.now(), "pi_fixture"));
        results.onPaymentSucceeded(success);
        results.onPaymentSucceeded(success);
        assertThat(orders.checkoutOrder(input.requestId(), fixture.user().getId())
                        .status())
                .isEqualTo("PAID");
        assertThat(inventory.sellableQuantity(fixture.variant().getId())).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payment_outbox WHERE order_id=?", Integer.class, order.getId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForList(
                        "SELECT kind FROM order_notifications WHERE order_id=?", String.class, order.getId()))
                .containsExactlyInAnyOrder("PLACED", "PAYMENT_RETRY_REQUIRED", "PAID");
    }

    @Test
    void foreignCustomerCannotReadReplayOrPayAnUncertainPlacement() {
        var owner = fixture();
        var foreign = fixture();
        var input = placement(owner);
        var pending = orders.placeCheckout(owner.user().getId(), null, null, input);
        assertThatThrownBy(() ->
                        orders.checkoutOrder(input.requestId(), foreign.user().getId()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> orders.placeCheckout(foreign.user().getId(), null, null, input))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() ->
                        orders.paymentSession(input.requestId(), foreign.user().getId(), null))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(stripe, never()).session(any());
        assertThat(orders.placeCheckout(owner.user().getId(), null, null, input).getId())
                .isEqualTo(pending.getId());
        assertThat(orders.checkoutOrder(input.requestId(), owner.user().getId()).status())
                .isEqualTo("PENDING");
        assertThat(inventory.sellableQuantity(owner.variant().getId())).isEqualTo(5);
        assertThat(inventory.sellableQuantity(foreign.variant().getId())).isEqualTo(6);
    }

    @Test
    void paymentSessionRequiresCustomerOwnershipAndIndependentGuestProof() throws Exception {
        var owner = fixture();
        var foreign = fixture();
        var input = placement(owner);
        var order = orders.placeCheckout(owner.user().getId(), null, null, input);
        assertThatThrownBy(() ->
                        orders.paymentSession(input.requestId(), foreign.user().getId(), null))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(stripe, never()).session(any());
        assertThat(orders.paymentSession(input.requestId(), owner.user().getId(), null)
                        .status())
                .isEqualTo("requires_payment_method");
        verify(stripe).session(request(order.getId()).eventId());

        String source = GuestCartCredentials.generate(), grant = GuestCartCredentials.generate();
        carts.createGuestCart(source);
        carts.addGuestItem(source, owner.variant().getId(), 1);
        var guestInput = new Placement(
                UUID.randomUUID(),
                orders.previewCheckout(null, source, selection()).quoteVersion(),
                selection());
        orders.placeCheckout(null, source, grant, guestInput);
        assertThatThrownBy(() -> orders.paymentSession(guestInput.requestId(), null, GuestCartCredentials.generate()))
                .hasMessageContaining("Guest order");
        assertThat(orders.paymentSession(guestInput.requestId(), null, grant).clientSecret())
                .isNotBlank();
    }

    @Test
    void unavailableSandboxDoesNotConsumeCartAllocateStockOrSendMail() {
        var fixture = fixture();
        var input = placement(fixture);
        when(stripe.configuration()).thenReturn(new StripePaymentClient.Configuration(false, ""));
        assertThatThrownBy(() -> orders.placeCheckout(fixture.user().getId(), null, null, input))
                .hasMessageContaining("not configured");
        assertThat(inventory.sellableQuantity(fixture.variant().getId())).isEqualTo(6);
        var itemCount = new TransactionTemplate(transactions)
                .execute(status ->
                        carts.getMyCart(fixture.user().getId()).getItems().size());
        assertThat(itemCount).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM checkout_requests WHERE request_id=?", Integer.class, input.requestId()))
                .isZero();
    }

    @Test
    void notificationEnqueueRollsBackAndRetriesSmtpWithoutDuplicateLogicalMail() {
        var fixture = fixture();
        var input = placement(fixture);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var order = orders.placeCheckout(fixture.user().getId(), null, null, input);
            notifications.enqueue(order, "PLACED");
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM checkout_requests WHERE request_id=?", Integer.class, input.requestId()))
                .isZero();
        var order = orders.placeCheckout(fixture.user().getId(), null, null, input);
        notifications.enqueue(order, "PLACED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM order_notifications WHERE order_id=?", Integer.class, order.getId()))
                .isEqualTo(1);
        doThrow(new MailSendException("fixture SMTP unavailable")).when(mail).send(any(MimeMessage.class));
        notifications.deliver();
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM order_notifications WHERE order_id=?", String.class, order.getId()))
                .isEqualTo("PENDING");
        jdbc.update("UPDATE order_notifications SET next_attempt_at=now() WHERE order_id=?", order.getId());
        doNothing().when(mail).send(any(MimeMessage.class));
        notifications.deliver();
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM order_notifications WHERE order_id=?", String.class, order.getId()))
                .isEqualTo("SENT");
    }
}
