package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ee.bytecore.backend.config.CheckoutSettings;
import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.entities.user.UserAddress;
import ee.bytecore.backend.entities.user.UserPaymentMethod;
import ee.bytecore.backend.enums.OrderStatus;
import ee.bytecore.backend.enums.PaymentMethodType;
import ee.bytecore.backend.integration.payment.PaymentOutboxPublisher;
import ee.bytecore.backend.integration.payment.PaymentResultListener;
import ee.bytecore.backend.integration.payment.event.PaymentFailedEvent;
import ee.bytecore.backend.integration.payment.event.PaymentSucceededEvent;
import ee.bytecore.backend.repositories.payment.OrderRepository;
import ee.bytecore.backend.repositories.user.UserAddressRepository;
import ee.bytecore.backend.repositories.user.UserPaymentMethodRepository;
import ee.bytecore.backend.repositories.user.UserRepository;
import ee.bytecore.backend.security.GuestCartCredentials;
import ee.bytecore.backend.services.CheckoutValues.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@SpringBootTest(
        properties = {
            "spring.kafka.bootstrap-servers=127.0.0.1:1",
            "spring.kafka.listener.auto-startup=false",
            "spring.docker.compose.enabled=false"
        })
@Import(PostgresTestConfiguration.class)
@Tag("integration")
@Timeout(60)
class CheckoutIntegrationTest {
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
    UserAddressRepository addresses;

    @Autowired
    UserPaymentMethodRepository methods;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    CheckoutSettings settings;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PaymentResultListener results;

    @MockitoBean
    PaymentOutboxPublisher outboxPublisher;

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Address ADDRESS =
            new Address("Ada", "Example", "Tallinn", "EE", "10111", "Example 1", null, null);

    private record Fixture(User user, ProductVariant variant, long cartId, long warehouseId) {}

    private Fixture fixture(int quantity, int stock) {
        String key = UUID.randomUUID().toString();
        User user = users.save(User.create(key, key + "@example.com", LocalDate.of(1990, 1, 1)));
        var product = products.create("Snapshot product " + key, key, null, null);
        var variant = variants.create(product.getId(), key, new BigDecimal("19.99"), Map.of("size", "M"), null, null);
        var warehouse = warehouses.create(key, "Fixture");
        inventory.setInventory(variant.getId(), warehouse.getId(), stock);
        var cart = carts.getMyCart(user.getId());
        if (quantity > 0) carts.addItem(user.getId(), variant.getId(), quantity);
        return new Fixture(user, variant, cart.getId(), warehouse.getId());
    }

    private Selection selection(String shipping) {
        return new Selection(
                "ada@example.com",
                null,
                shipping,
                null,
                "PICKUP".equals(shipping) ? null : ADDRESS,
                null,
                null,
                false,
                "SIMULATED",
                null);
    }

    private Placement placement(Fixture fixture, Selection selection) {
        return new Placement(
                UUID.randomUUID(),
                orders.previewCheckout(fixture.user().getId(), null, selection).quoteVersion(),
                selection);
    }

    private int stock(Fixture fixture) {
        return inventory.findAllByProductVariantId(fixture.variant().getId()).stream()
                .mapToInt(value -> value.getQuantity())
                .sum();
    }

    private int cartCount(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM cart_items WHERE cart_id=?", Integer.class, id);
    }

    @ParameterizedTest
    @CsvSource({"STANDARD,4.99,44.97", "EXPRESS,9.99,49.97", "PICKUP,0.00,39.98"})
    void previewAndPlacementUseExactShippingTotals(String shipping, String charge, String total) {
        var f = fixture(2, 10);
        var s = selection(shipping);
        var preview = orders.previewCheckout(f.user().getId(), null, s);
        assertThat(preview.placeable()).isTrue();
        assertThat(preview.lines().getFirst().unitPrice()).isEqualByComparingTo("19.99");
        assertThat(preview.lines().getFirst().subtotal()).isEqualByComparingTo("39.98");
        assertThat(preview.totals().merchandiseSubtotal()).isEqualByComparingTo("39.98");
        assertThat(preview.totals().shippingCharge()).isEqualByComparingTo(charge);
        assertThat(preview.totals().total()).isEqualByComparingTo(total);
        assertThat(preview.totals().currency()).isEqualTo("EUR");
        assertThat(stock(f)).isEqualTo(10);
        assertThat(cartCount(f.cartId())).isEqualTo(1);
        assertThat(orderRepository.findAllByUserId(f.user().getId())).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM checkout_requests WHERE source_cart_id=?", Integer.class, f.cartId()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payment_outbox WHERE payload->>'userId'=?",
                        Integer.class,
                        f.user().getId().toString()))
                .isZero();
        var input = new Placement(UUID.randomUUID(), preview.quoteVersion(), s);
        var order = orders.placeCheckout(f.user().getId(), null, null, input);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getTotalAmount()).isEqualByComparingTo(total);
        assertThat(stock(f)).isEqualTo(8);
        assertThat(cartCount(f.cartId())).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT payload->>'amount' FROM payment_outbox WHERE order_id=?", String.class, order.getId()))
                .isEqualTo(total);
        assertThat(jdbc.queryForObject(
                        "SELECT payload->>'currency' FROM payment_outbox WHERE order_id=?",
                        String.class,
                        order.getId()))
                .isEqualTo("EUR");
        assertThat(orders.placeCheckout(f.user().getId(), null, null, input).getPublicId())
                .isEqualTo(order.getPublicId());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payment_outbox WHERE order_id=?", Integer.class, order.getId()))
                .isEqualTo(1);
        assertThat(orders.checkoutOrder(input.requestId(), f.user().getId())
                        .totals()
                        .total())
                .isEqualByComparingTo(total);
    }

    @Test
    void changedPricesQuantitiesAndShippingRequireRenewedAcceptance() {
        var f = fixture(1, 10);
        var s = selection("STANDARD");
        var input = placement(f, s);
        variants.update(f.variant().getId(), null, new BigDecimal("20.00"), null, null, null, null);
        assertThatThrownBy(() -> orders.placeCheckout(f.user().getId(), null, null, input))
                .hasMessageContaining("quote changed");
        var refreshed = placement(f, s);
        var itemId = jdbc.queryForObject("SELECT id FROM cart_items WHERE cart_id=?", Long.class, f.cartId());
        carts.updateItemQuantity(f.user().getId(), itemId, 2);
        assertThatThrownBy(() -> orders.placeCheckout(f.user().getId(), null, null, refreshed))
                .hasMessageContaining("quote changed");
        var third = placement(f, s);
        BigDecimal original = settings.getStandardCharge();
        try {
            settings.setStandardCharge(new BigDecimal("5.99"));
            assertThatThrownBy(() -> orders.placeCheckout(f.user().getId(), null, null, third))
                    .hasMessageContaining("quote changed");
            var accepted = placement(f, s);
            assertThat(orders.placeCheckout(f.user().getId(), null, null, accepted)
                            .getTotalAmount())
                    .isEqualByComparingTo("45.99");
        } finally {
            settings.setStandardCharge(original);
        }
    }

    @Test
    void invalidCartNeverCreatesOrderOrConsumesItems() {
        var empty = fixture(0, 1);
        var preview = orders.previewCheckout(empty.user().getId(), null, selection("PICKUP"));
        assertThat(preview.issues()).extracting(Issue::code).contains("EMPTY_CART");
        assertThatThrownBy(() -> orders.placeCheckout(
                        empty.user().getId(),
                        null,
                        null,
                        new Placement(UUID.randomUUID(), preview.quoteVersion(), selection("PICKUP"))))
                .hasMessageContaining("empty cart");
        var f = fixture(2, 1);
        var input = placement(f, selection("PICKUP"));
        assertThatThrownBy(() -> orders.placeCheckout(f.user().getId(), null, null, input))
                .hasMessageContaining("stock");
        variants.update(f.variant().getId(), null, null, null, null, null, false);
        assertThat(orders.previewCheckout(f.user().getId(), null, selection("PICKUP"))
                        .issues())
                .extracting(Issue::code)
                .contains("VARIANT_UNAVAILABLE");
        assertThatThrownBy(() -> orders.placeCheckout(f.user().getId(), null, null, input))
                .hasMessageContaining("unavailable");
        assertThat(stock(f)).isEqualTo(1);
        assertThat(cartCount(f.cartId())).isEqualTo(1);
        assertThat(orderRepository.findAllByUserId(f.user().getId())).isEmpty();
    }

    @Test
    void savedSelectionsAreOwnedAndSnapshotsSurviveEdits() {
        var f = fixture(1, 10);
        var address = addresses.save(
                UserAddress.create(f.user(), "Ada", "Example", "Tallinn", "EE", "10111", "Old street", null, null));
        var method = methods.save(UserPaymentMethod.create(f.user(), "simulated", PaymentMethodType.CARD));
        var s = new Selection(
                null, null, "STANDARD", address.getId(), null, null, null, true, "SIMULATED", method.getId());
        var input = placement(f, s);
        var order = orders.placeCheckout(f.user().getId(), null, null, input);
        var before = orders.checkoutOrder(input.requestId(), f.user().getId());
        variants.update(
                f.variant().getId(),
                "changed-" + UUID.randomUUID(),
                new BigDecimal("30.00"),
                Map.of("size", "XL"),
                null,
                null,
                null);
        products.update(f.variant().getProduct().getId(), "New name", null, null, null);
        address.setAddressLine1("New street");
        addresses.save(address);
        addresses.deleteById(address.getId());
        methods.deleteById(method.getId());
        BigDecimal originalCharge = settings.getStandardCharge();
        String originalPickup = settings.getPickupName();
        try {
            settings.setStandardCharge(new BigDecimal("14.99"));
            settings.setPickupName("Changed development fixture");
            assertThat(orders.checkoutOrder(input.requestId(), f.user().getId()))
                    .isEqualTo(before);
        } finally {
            settings.setStandardCharge(originalCharge);
            settings.setPickupName(originalPickup);
        }
        assertThat(before.details().shippingAddress().addressLine1()).isEqualTo("Old street");
        assertThat(before.lines().getFirst().attributes()).containsEntry("size", "M");
        assertThat(orders.placeCheckout(f.user().getId(), null, null, input).getPublicId())
                .isEqualTo(order.getPublicId());
        var other = fixture(1, 10);
        assertThatThrownBy(() ->
                        orders.checkoutOrder(input.requestId(), other.user().getId()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> orders.placeCheckout(other.user().getId(), null, null, input))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET total_amount=1 WHERE id=?", order.getId()))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE order_items SET quantity=5 WHERE order_id=?", order.getId()))
                .hasMessageContaining("immutable");
    }

    @Test
    void rejectForeignSavedSelectionsAndInvalidInlineAddress() {
        var f = fixture(1, 10);
        var other = fixture(1, 10);
        var address = addresses.save(UserAddress.create(
                other.user(), "Other", "Owner", "Tallinn", "EE", "10111", "Other street", null, null));
        var method = methods.save(UserPaymentMethod.create(other.user(), "simulated", PaymentMethodType.CARD));
        assertThatThrownBy(() -> orders.previewCheckout(
                        f.user().getId(),
                        null,
                        new Selection(
                                null, null, "STANDARD", address.getId(), null, null, null, false, "SIMULATED", null)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> orders.previewCheckout(
                        f.user().getId(),
                        null,
                        new Selection(
                                null, null, "PICKUP", null, null, null, null, false, "SIMULATED", method.getId())))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        var invalid = new Address("", "Example", "Tallinn", "EE", "10111", "Street", null, null);
        assertThatThrownBy(() -> orders.previewCheckout(
                        f.user().getId(),
                        null,
                        new Selection(null, null, "STANDARD", null, invalid, null, null, false, "SIMULATED", null)))
                .hasMessageContaining("First name");
        var foreign = new Address("Ada", "Example", "Berlin", "DE", "10111", "Street", null, null);
        assertThatThrownBy(() -> orders.previewCheckout(
                        f.user().getId(),
                        null,
                        new Selection(null, null, "STANDARD", null, foreign, null, null, false, "SIMULATED", null)))
                .hasMessageContaining("country");
        var unsupported = methods.save(UserPaymentMethod.create(f.user(), "stripe", PaymentMethodType.CARD));
        assertThatThrownBy(() -> orders.previewCheckout(
                        f.user().getId(),
                        null,
                        new Selection(
                                null, null, "PICKUP", null, null, null, null, false, "SIMULATED", unsupported.getId())))
                .hasMessageContaining("compatible");
    }

    @Test
    void guestReplayAfterConsumptionRequiresIndependentGrantAndExpires() {
        var f = fixture(0, 10);
        String credential = GuestCartCredentials.generate();
        String receiptCredential = GuestCartCredentials.generate();
        var guest = carts.createGuestCart(credential);
        carts.addGuestItem(credential, f.variant().getId(), 2);
        var s = selection("EXPRESS");
        var preview = orders.previewCheckout(null, credential, s);
        var input = new Placement(UUID.randomUUID(), preview.quoteVersion(), s);
        var order = orders.placeCheckout(null, credential, receiptCredential, input);
        assertThat(order.getUser()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carts WHERE id=?", Integer.class, guest.getId()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT payload->'userId' = 'null'::jsonb FROM payment_outbox WHERE order_id=?",
                        Boolean.class,
                        order.getId()))
                .isTrue();
        assertThat(orders.placeCheckout(null, null, receiptCredential, input).getPublicId())
                .isEqualTo(order.getPublicId());
        assertThat(orders.placeCheckout(null, credential, receiptCredential, input)
                        .getPublicId())
                .isEqualTo(order.getPublicId());
        assertThat(orders.guestOrder(order.getPublicId(), null, receiptCredential)
                        .totals()
                        .total())
                .isEqualByComparingTo("49.97");
        assertThatThrownBy(() -> orders.guestOrder(order.getPublicId(), null, credential))
                .hasMessageContaining("unavailable");
        assertThatThrownBy(() -> orders.guestOrder(null, input.requestId(), GuestCartCredentials.generate()))
                .hasMessageContaining("unavailable");
        assertThatThrownBy(() -> orders.requireReadable(order, null, false))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        var altered = new Placement(input.requestId(), input.acceptedQuoteVersion(), selection("PICKUP"));
        assertThatThrownBy(() -> orders.placeCheckout(null, null, receiptCredential, altered))
                .hasMessageContaining("different");
        jdbc.update(
                "UPDATE checkout_requests SET guest_expires_at=now()-interval '1 second' WHERE request_id=?",
                input.requestId());
        assertThatThrownBy(() -> orders.placeCheckout(null, null, receiptCredential, input))
                .hasMessageContaining("expired");
        assertThat(stock(f)).isEqualTo(8);
    }

    @Test
    void databaseFailureRollsBackCartInventoryReceiptAndOutbox() {
        var f = fixture(1, 10);
        var input = placement(f, selection("STANDARD"));
        String suffix = "checkout_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + suffix + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.request_id='"
                + input.requestId()
                + "'::uuid THEN RAISE EXCEPTION 'Checkout rollback fixture'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER " + suffix + " BEFORE INSERT ON checkout_requests FOR EACH ROW EXECUTE FUNCTION "
                + suffix + "()");
        try {
            assertThatThrownBy(() -> orders.placeCheckout(f.user().getId(), null, null, input))
                    .hasMessageContaining("rollback fixture");
            assertThat(stock(f)).isEqualTo(10);
            assertThat(cartCount(f.cartId())).isEqualTo(1);
            assertThat(orderRepository.findAllByUserId(f.user().getId())).isEmpty();
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM checkout_requests WHERE request_id=?",
                            Integer.class,
                            input.requestId()))
                    .isZero();
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM payment_outbox WHERE payload->>'userId'=?",
                            Integer.class,
                            f.user().getId().toString()))
                    .isZero();
        } finally {
            jdbc.execute("DROP TRIGGER " + suffix + " ON checkout_requests");
            jdbc.execute("DROP FUNCTION " + suffix + "()");
        }
        orders.placeCheckout(f.user().getId(), null, null, input);
    }

    @Test
    void simultaneousRequestsOnSameCartCannotDoublePlace() throws Exception {
        var f = fixture(1, 10);
        var input = placement(f, selection("PICKUP"));
        var outcomes = race(
                () -> orders.placeCheckout(f.user().getId(), null, null, input),
                () -> orders.placeCheckout(f.user().getId(), null, null, input));
        assertThat(outcomes).noneMatch(value -> value instanceof Throwable);
        assertThat(orderRepository.findAllByUserId(f.user().getId())).hasSize(1);
        assertThat(stock(f)).isEqualTo(9);
        var second = fixture(1, 10);
        var a = placement(second, selection("PICKUP"));
        var b = new Placement(UUID.randomUUID(), a.acceptedQuoteVersion(), a.checkout());
        var separate = race(
                () -> orders.placeCheckout(second.user().getId(), null, null, a),
                () -> orders.placeCheckout(second.user().getId(), null, null, b));
        assertThat(separate).filteredOn(value -> value instanceof Throwable).hasSize(1);
        assertThat(orderRepository.findAllByUserId(second.user().getId())).hasSize(1);
        assertThat(stock(second)).isEqualTo(9);
    }

    @Test
    void cartMutationAndGuestMergeSerializeWithPlacement() throws Exception {
        var f = fixture(1, 10);
        var input = placement(f, selection("PICKUP"));
        var itemId = jdbc.queryForObject("SELECT id FROM cart_items WHERE cart_id=?", Long.class, f.cartId());
        race(
                () -> orders.placeCheckout(f.user().getId(), null, null, input),
                () -> carts.updateItemQuantity(f.user().getId(), itemId, 2));
        var purchased = orderRepository.findAllByUserId(f.user().getId());
        if (purchased.isEmpty()) {
            assertThat(stock(f)).isEqualTo(10);
            assertThat(jdbc.queryForObject(
                            "SELECT quantity FROM cart_items WHERE cart_id=?", Integer.class, f.cartId()))
                    .isEqualTo(2);
        } else {
            assertThat(purchased).hasSize(1);
            assertThat(stock(f)).isEqualTo(9);
            assertThat(cartCount(f.cartId())).isZero();
        }
        var g = fixture(0, 10);
        String credential = GuestCartCredentials.generate();
        String grant = GuestCartCredentials.generate();
        carts.createGuestCart(credential);
        carts.addGuestItem(credential, g.variant().getId(), 1);
        var guestInput = new Placement(
                UUID.randomUUID(),
                orders.previewCheckout(null, credential, selection("PICKUP")).quoteVersion(),
                selection("PICKUP"));
        var outcomes = race(
                () -> orders.placeCheckout(null, credential, grant, guestInput),
                () -> carts.mergeGuestCart(g.user().getId(), credential, UUID.randomUUID()));
        assertThat(outcomes).filteredOn(value -> value instanceof Throwable).hasSize(1);
        int bought = jdbc.queryForObject(
                "SELECT count(*) FROM checkout_requests WHERE request_id=?", Integer.class, guestInput.requestId());
        assertThat(stock(g)).isEqualTo(10 - bought);
        assertThat(cartCount(g.cartId())).isEqualTo(1 - bought);
    }

    @Test
    void limitedStockCannotBeOversoldAcrossOwners() throws Exception {
        var f = fixture(1, 1);
        var other = fixture(0, 0);
        carts.addItem(other.user().getId(), f.variant().getId(), 1);
        var a = placement(f, selection("PICKUP"));
        var b = new Placement(
                UUID.randomUUID(),
                orders.previewCheckout(other.user().getId(), null, selection("PICKUP"))
                        .quoteVersion(),
                selection("PICKUP"));
        var outcomes = race(
                () -> orders.placeCheckout(f.user().getId(), null, null, a),
                () -> orders.placeCheckout(other.user().getId(), null, null, b));
        assertThat(outcomes).filteredOn(value -> value instanceof Throwable).hasSize(1);
        assertThat(stock(f)).isZero();
        assertThat(cartCount(f.cartId()) + cartCount(other.cartId())).isEqualTo(1);
    }

    @Test
    void guestQuantityChangesAndAccountDeletionSerializeWithCheckout() throws Exception {
        var f = fixture(0, 10);
        String source = GuestCartCredentials.generate();
        String grant = GuestCartCredentials.generate();
        var cart = carts.createGuestCart(source);
        carts.addGuestItem(source, f.variant().getId(), 1);
        var itemId = jdbc.queryForObject("SELECT id FROM cart_items WHERE cart_id=?", Long.class, cart.getId());
        var s = selection("PICKUP");
        var input = new Placement(
                UUID.randomUUID(), orders.previewCheckout(null, source, s).quoteVersion(), s);
        var outcomes = race(
                () -> orders.placeCheckout(null, source, grant, input),
                () -> carts.updateGuestQuantity(source, itemId, 2));
        assertThat(outcomes).filteredOn(value -> value instanceof Throwable).hasSize(1);
        int placed = jdbc.queryForObject(
                "SELECT count(*) FROM checkout_requests WHERE request_id=?", Integer.class, input.requestId());
        assertThat(stock(f)).isEqualTo(10 - placed);
        if (placed == 0)
            assertThat(carts.getGuestCart(source).getItems().getFirst().getQuantity())
                    .isEqualTo(2);
        else
            assertThat(jdbc.queryForObject("SELECT count(*) FROM carts WHERE id=?", Integer.class, cart.getId()))
                    .isZero();

        var auth = fixture(1, 10);
        var authInput = placement(auth, s);
        race(
                () -> orders.placeCheckout(auth.user().getId(), null, null, authInput),
                () -> jdbc.update(
                        "UPDATE users SET deletion_identity_id=? WHERE id=?",
                        UUID.randomUUID(),
                        auth.user().getId()));
        int bought = jdbc.queryForObject(
                "SELECT count(*) FROM checkout_requests WHERE request_id=?", Integer.class, authInput.requestId());
        assertThat(stock(auth)).isEqualTo(10 - bought);
        assertThat(cartCount(auth.cartId())).isEqualTo(1 - bought);
        assertThatThrownBy(() -> orders.placeCheckout(auth.user().getId(), null, null, authInput))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void guestLinesUseExistingMultiWarehousePolicyAndFailureRestocksExactRows() throws Exception {
        var f = fixture(0, 1);
        var extraWarehouse = warehouses.create(UUID.randomUUID().toString(), "Fixture 2");
        inventory.setInventory(f.variant().getId(), extraWarehouse.getId(), 4);
        String source = GuestCartCredentials.generate();
        String grant = GuestCartCredentials.generate();
        carts.createGuestCart(source);
        carts.addGuestItem(source, f.variant().getId(), 2);
        var input = new Placement(
                UUID.randomUUID(),
                orders.previewCheckout(null, source, selection("STANDARD")).quoteVersion(),
                selection("STANDARD"));
        var order = orders.placeCheckout(null, source, grant, input);
        assertThat(jdbc.queryForObject(
                        "SELECT quantity FROM inventory WHERE product_variant_id=? AND warehouse_id=?",
                        Integer.class,
                        f.variant().getId(),
                        f.warehouseId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT quantity FROM inventory WHERE product_variant_id=? AND warehouse_id=?",
                        Integer.class,
                        f.variant().getId(),
                        extraWarehouse.getId()))
                .isEqualTo(2);
        UUID eventId = UUID.fromString(jdbc.queryForObject(
                "SELECT payload->>'eventId' FROM payment_outbox WHERE order_id=?", String.class, order.getId()));
        var failure = new PaymentFailedEvent(
                UUID.randomUUID(), eventId, order.getId(), UUID.randomUUID(), "simulated decline", Instant.now());
        results.onPaymentFailed(JSON.writeValueAsString(failure));
        results.onPaymentFailed(JSON.writeValueAsString(failure));
        assertThat(stock(f)).isEqualTo(5);
        assertThat(orders.guestOrder(null, input.requestId(), grant).status()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM carts WHERE guest_credential_hash=?",
                        Integer.class,
                        GuestCartCredentials.hash(source)))
                .isZero();
    }

    @Test
    void guestPlacementDatabaseFailureRetainsSourceAndCreatesNoGrant() {
        var f = fixture(0, 10);
        String source = GuestCartCredentials.generate();
        String grant = GuestCartCredentials.generate();
        var cart = carts.createGuestCart(source);
        carts.addGuestItem(source, f.variant().getId(), 2);
        var input = new Placement(
                UUID.randomUUID(),
                orders.previewCheckout(null, source, selection("PICKUP")).quoteVersion(),
                selection("PICKUP"));
        int requestsBefore = jdbc.queryForObject("SELECT count(*) FROM payment_outbox", Integer.class);
        String name = "guest_checkout_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.request_id='"
                + input.requestId()
                + "'::uuid THEN RAISE EXCEPTION 'Guest checkout rollback fixture'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER " + name + " BEFORE INSERT ON checkout_requests FOR EACH ROW EXECUTE FUNCTION "
                + name + "()");
        try {
            assertThatThrownBy(() -> orders.placeCheckout(null, source, grant, input))
                    .hasMessageContaining("rollback fixture");
            assertThat(carts.getGuestCart(source).getItems().getFirst().getQuantity())
                    .isEqualTo(2);
            assertThat(stock(f)).isEqualTo(10);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM checkout_requests WHERE source_cart_id=?",
                            Integer.class,
                            cart.getId()))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox", Integer.class))
                    .isEqualTo(requestsBefore);
        } finally {
            jdbc.execute("DROP TRIGGER " + name + " ON checkout_requests");
            jdbc.execute("DROP FUNCTION " + name + "()");
        }
    }

    @Test
    void activeAccountIsRecheckedOnPlacementAndReplay() {
        var f = fixture(1, 10);
        var input = placement(f, selection("PICKUP"));
        orders.placeCheckout(f.user().getId(), null, null, input);
        jdbc.update(
                "UPDATE users SET deletion_identity_id=? WHERE id=?",
                UUID.randomUUID(),
                f.user().getId());
        assertThatThrownBy(() -> orders.placeCheckout(f.user().getId(), null, null, input))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(
                        () -> orders.checkoutOrder(input.requestId(), f.user().getId()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        var g = fixture(1, 10);
        var second = placement(g, selection("PICKUP"));
        jdbc.update("UPDATE users SET deleted=true WHERE id=?", g.user().getId());
        assertThatThrownBy(() -> orders.placeCheckout(g.user().getId(), null, null, second))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(stock(g)).isEqualTo(10);
        assertThat(cartCount(g.cartId())).isEqualTo(1);
    }

    @Test
    void guestPaymentResultsPreserveCorrelationAndRestoreOnce() throws Exception {
        var f = fixture(0, 10);
        String source = GuestCartCredentials.generate();
        String grant = GuestCartCredentials.generate();
        carts.createGuestCart(source);
        carts.addGuestItem(source, f.variant().getId(), 1);
        var input = new Placement(
                UUID.randomUUID(),
                orders.previewCheckout(null, source, selection("PICKUP")).quoteVersion(),
                selection("PICKUP"));
        var order = orders.placeCheckout(null, source, grant, input);
        UUID eventId = UUID.fromString(jdbc.queryForObject(
                "SELECT payload->>'eventId' FROM payment_outbox WHERE order_id=?", String.class, order.getId()));
        UUID paymentId = UUID.randomUUID();
        var success = new PaymentSucceededEvent(UUID.randomUUID(), eventId, order.getId(), paymentId, Instant.now());
        results.onPaymentSucceeded(JSON.writeValueAsString(success));
        results.onPaymentSucceeded(JSON.writeValueAsString(success));
        assertThat(orders.guestOrder(null, input.requestId(), grant).status()).isEqualTo("PAID");
        orders.updateStatus(order.getId(), OrderStatus.CANCELLED);
        assertThat(stock(f)).isEqualTo(10);
        var failure = new PaymentFailedEvent(
                UUID.randomUUID(), eventId, order.getId(), UUID.randomUUID(), "declined", Instant.now());
        results.onPaymentFailed(JSON.writeValueAsString(failure));
        assertThat(stock(f)).isEqualTo(10);
        assertThat(cartCount(f.cartId())).isZero();
    }

    private List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.function.Function<Callable<?>, Callable<Object>> task = call -> () -> {
                ready.countDown();
                if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Race start timed out");
                try {
                    return call.call();
                } catch (RuntimeException failure) {
                    return failure;
                }
            };
            var a = executor.submit(task.apply(first));
            var b = executor.submit(task.apply(second));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }
}
