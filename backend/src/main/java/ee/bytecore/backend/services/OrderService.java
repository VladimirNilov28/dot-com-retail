package ee.bytecore.backend.services;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ee.bytecore.backend.config.CheckoutSettings;
import ee.bytecore.backend.entities.cart.Cart;
import ee.bytecore.backend.entities.cart.CartItem;
import ee.bytecore.backend.entities.inventory.Inventory;
import ee.bytecore.backend.entities.payment.CheckoutRequest;
import ee.bytecore.backend.entities.payment.Order;
import ee.bytecore.backend.entities.payment.OrderItem;
import ee.bytecore.backend.entities.payment.OrderRefund;
import ee.bytecore.backend.entities.payment.OrderStatusEvent;
import ee.bytecore.backend.entities.payment.RefundRequest;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.entities.user.UserAddress;
import ee.bytecore.backend.enums.OrderStatus;
import ee.bytecore.backend.exceptions.CheckoutException;
import ee.bytecore.backend.integration.payment.PaymentEventPublisher;
import ee.bytecore.backend.integration.payment.event.PaymentRequestedEvent;
import ee.bytecore.backend.integration.payment.event.RefundRequestedEvent;
import ee.bytecore.backend.repositories.payment.CheckoutRequestRepository;
import ee.bytecore.backend.repositories.payment.OrderItemRepository;
import ee.bytecore.backend.repositories.payment.OrderRefundRepository;
import ee.bytecore.backend.repositories.payment.OrderRepository;
import ee.bytecore.backend.repositories.payment.OrderStatusEventRepository;
import ee.bytecore.backend.repositories.payment.PaymentOutboxEventRepository;
import ee.bytecore.backend.repositories.payment.RefundRequestRepository;
import ee.bytecore.backend.repositories.user.UserPaymentMethodRepository;
import ee.bytecore.backend.repositories.user.UserRepository;
import ee.bytecore.backend.security.GuestCartCredentials;
import ee.bytecore.backend.services.CheckoutValues.*;
import ee.bytecore.backend.services.audit.AuditService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;

@Service
public class OrderService {

    // The catalog/checkout has no per-order currency selection today; every
    // amount in the system (ProductVariant.price, Order.totalAmount, ...) is
    // implicitly this single currency. Kept as one named constant rather than
    // duplicated string literals so a future multi-currency change has one
    // place to start.
    private static final String DEFAULT_CURRENCY = "EUR";

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CartService cartService;
    private final InventoryService inventoryService;
    private final OrderStatusPublisher orderStatusPublisher;
    private final PaymentEventPublisher paymentEventPublisher;

    @Autowired
    private ObjectProvider<CheckoutSettings> checkoutSettings;

    @Autowired
    private ObjectProvider<DeliveryOptionService> deliveryOptions;

    @Autowired
    private ObjectProvider<CheckoutRequestRepository> checkoutRequests;

    @Autowired
    private ObjectProvider<UserRepository> checkoutUsers;

    @Autowired
    private ObjectProvider<UserAddressService> checkoutAddresses;

    @Autowired
    private ObjectProvider<UserPaymentMethodRepository> checkoutMethods;

    @Autowired
    private ObjectProvider<JdbcTemplate> checkoutJdbc;

    @Autowired
    private ObjectProvider<EntityManager> checkoutEntityManager;

    @Autowired
    private ObjectProvider<OrderRefundRepository> refunds;

    @Autowired
    private ObjectProvider<PaymentOutboxEventRepository> financialRequests;

    @Autowired
    private ObjectProvider<ee.bytecore.backend.integration.payment.StripePaymentClient> stripePayments;

    @Autowired
    private ObjectProvider<ee.bytecore.backend.integration.payment.OrderNotifications> notifications;

    @Autowired
    private ObjectProvider<OrderStatusEventRepository> statusEvents;

    @Autowired
    private ObjectProvider<RefundRequestRepository> refundRequests;

    @Autowired
    private ObjectProvider<AuditService> audits;

    public static final int MAX_PAGE_SIZE = 50;
    public static final int DEFAULT_PAGE_SIZE = 20;

    public record OrderFilter(OrderStatus status, Instant from, Instant to, String query) {}

    public record OrderPage(List<Order> items, long totalCount, int page, int size) {}

    private void notifyOrder(Order order, String kind) {
        notifyOrder(order, kind, "", null);
    }

    private void notifyOrder(Order order, String kind, String ref, String detail) {
        var sender = notifications == null ? null : notifications.getIfAvailable();
        if (sender != null) sender.enqueue(order, kind, ref, detail);
    }

    private void audit(String operation, Order order, Map<String, ?> details) {
        var audit = audits == null ? null : audits.getIfAvailable();
        if (audit != null) audit.record(operation, "ORDER", order.getPublicId(), details);
    }

    private void recordStatusEvent(Order order, OrderStatus from, OrderStatus to, String actorKind, String note) {
        var repository = statusEvents == null ? null : statusEvents.getIfAvailable();
        if (repository != null)
            repository.save(OrderStatusEvent.create(
                    order.getId(), from == null ? null : from.name(), to.name(), actorKind, note));
    }

    public OrderService(
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            CartService cartService,
            InventoryService inventoryService,
            OrderStatusPublisher orderStatusPublisher,
            PaymentEventPublisher paymentEventPublisher) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.cartService = cartService;
        this.inventoryService = inventoryService;
        this.orderStatusPublisher = orderStatusPublisher;
        this.paymentEventPublisher = paymentEventPublisher;
    }

    public Optional<Order> findById(Long id) {
        return orderRepository.findById(id);
    }

    public Optional<Order> findByPublicId(UUID publicId) {
        return orderRepository.findByPublicId(publicId);
    }

    public List<Order> findMyOrders(Long userId) {
        List<Order> orders = orderRepository.findAllByUserId(userId);
        orders.forEach(order -> requireReadable(order, userId, false));
        return orders;
    }

    @Transactional(readOnly = true)
    public OrderPage myOrders(Long userId, OrderFilter filter, Integer page, Integer size) {
        if (userId == null) throw new AccessDeniedException("Active account is required");
        var users = checkoutUsers == null ? null : checkoutUsers.getIfAvailable();
        if (users != null) {
            var user =
                    users.findById(userId).orElseThrow(() -> new AccessDeniedException("Active account is required"));
            if (user.isDeleted() || user.getDeletionIdentityId() != null)
                throw new AccessDeniedException("Active account is required");
        }
        OrderPage result = search(
                userId,
                filter == null ? null : new OrderFilter(filter.status(), filter.from(), filter.to(), null),
                page,
                size);
        result.items().forEach(order -> requireReadable(order, userId, false));
        return result;
    }

    /** Staff search across all orders; the caller is authorized by @PreAuthorize on the DataFetcher. */
    @Transactional(readOnly = true)
    public OrderPage staffOrders(OrderFilter filter, Integer page, Integer size) {
        return search(null, filter, page, size);
    }

    private OrderPage search(Long userId, OrderFilter filter, Integer page, Integer size) {
        int p = page == null ? 0 : page;
        int n = size == null ? DEFAULT_PAGE_SIZE : size;
        if (p < 0 || p > 10_000) throw new IllegalArgumentException("page must be between 0 and 10000");
        if (n < 1 || n > MAX_PAGE_SIZE)
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        OrderFilter f = filter == null ? new OrderFilter(null, null, null, null) : filter;
        if (f.from() != null && f.to() != null && !f.from().isBefore(f.to()))
            throw new IllegalArgumentException("from must be before to");
        String query =
                f.query() == null || f.query().isBlank() ? null : f.query().trim();
        if (query != null && query.length() > 254) throw new IllegalArgumentException("query is too long");
        var result = orderRepository.search(
                userId,
                f.status() == null ? null : f.status().name(),
                f.from() == null ? null : f.from().atOffset(java.time.ZoneOffset.UTC),
                f.to() == null ? null : f.to().atOffset(java.time.ZoneOffset.UTC),
                query,
                PageRequest.of(p, n));
        return new OrderPage(result.getContent(), result.getTotalElements(), p, n);
    }

    @Transactional(readOnly = true)
    public List<OrderStatusEvent> statusHistory(Long orderId) {
        return statusEvents.getObject().findAllByOrderIdOrderByOccurredAtAscIdAsc(orderId);
    }

    @Transactional(readOnly = true)
    public List<RefundRequest> refundRequests(Long orderId) {
        return refundRequests.getObject().findAllByOrderIdOrderByCreatedAtAsc(orderId);
    }

    @Transactional(readOnly = true)
    public OrderRecoveryValues.Eligibility refundEligibility(Order order) {
        boolean open = refundRequests
                .getObject()
                .findFirstByOrderIdAndStatus(order.getId(), "REQUESTED")
                .isPresent();
        return refundEligibility(
                order, refunds.getObject().findByOrderId(order.getId()).isPresent(), open);
    }

    static OrderRecoveryValues.Eligibility refundEligibility(Order order, boolean refundExists, boolean openRequest) {
        if (order.getStatus() == OrderStatus.CANCELLED)
            return new OrderRecoveryValues.Eligibility(false, "ORDER_CANCELLED");
        if (order.getStatus() == OrderStatus.PENDING || !"SUCCEEDED".equals(order.getPaymentStatus()))
            return new OrderRecoveryValues.Eligibility(false, "NOT_PAID");
        if (refundExists) return new OrderRecoveryValues.Eligibility(false, "ALREADY_REFUNDED");
        if (openRequest) return new OrderRecoveryValues.Eligibility(false, "REQUEST_PENDING");
        return new OrderRecoveryValues.Eligibility(true, null);
    }

    @Transactional(readOnly = true)
    public Order findReadableByPublicId(UUID publicId, Long userId, boolean staff) {
        Order order = orderRepository
                .findByPublicId(publicId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        return requireReadable(order, userId, staff);
    }

    public List<OrderItem> findItems(Long orderId) {
        return orderItemRepository.findAllByOrderId(orderId);
    }

    /**
     * Resolves an owned {@link OrderItem} by id - used by the nested
     * {@code OrderItem.order} resolver so it can't be used to read another
     * user's order item.
     */
    public OrderItem getOwnedOrderItem(Long orderItemId, Long currentUserId, boolean isStaff) {
        OrderItem item = orderItemRepository
                .findById(orderItemId)
                .orElseThrow(() ->
                        new EntityNotFoundException(String.format("OrderItem with id %s not found", orderItemId)));
        requireReadable(item.getOrder(), currentUserId, isStaff);
        return item;
    }

    @Transactional
    public Order createOrder(Long userId) {
        Cart cart = cartService.getMyCartForUpdate(userId);
        return persistOrder(cart, userId, null);
    }

    private Order persistOrder(Cart cart, Long userId, Snapshot snapshot) {
        List<CartItem> cartItems = new ArrayList<>(cart.getItems());
        cartItems.sort(Comparator.comparing(item -> item.getProductVariant().getId()));
        if (cartItems.isEmpty()) {
            throw new IllegalArgumentException("Cannot create an order from an empty cart");
        }

        BigDecimal totalAmount = BigDecimal.ZERO;
        for (CartItem cartItem : cartItems) {
            totalAmount = totalAmount.add(
                    cartItem.getProductVariant().getPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity())));
        }

        // Allocate (lock + verify + decrement) stock for every cart item
        // first. If any item is insufficient, this throws and the whole
        // transaction rolls back before any Order/OrderItem is persisted and
        // before the cart is touched - no partial updates.
        Map<CartItem, Inventory> allocations = new LinkedHashMap<>();
        for (CartItem cartItem : cartItems) {
            Inventory inventory = inventoryService.allocateAndDecrement(
                    cartItem.getProductVariant().getId(), cartItem.getQuantity());
            allocations.put(cartItem, inventory);
        }

        if (snapshot != null) totalAmount = snapshot.totals().total();
        Order order = Order.create(cart.getUser(), OrderStatus.PENDING, totalAmount);
        order.setPaymentStatus("PENDING");
        if (snapshot != null) order.initializeCheckout(snapshot);
        Order saved = orderRepository.save(order);

        for (CartItem cartItem : cartItems) {
            OrderItem item = OrderItem.create(
                    saved,
                    cartItem.getProductVariant(),
                    allocations.get(cartItem),
                    cartItem.getQuantity(),
                    cartItem.getProductVariant().getPrice());
            if (snapshot != null) {
                item.initializeCheckout(snapshot.lines().stream()
                        .filter(line -> line.productVariantId()
                                .equals(cartItem.getProductVariant().getId()))
                        .findFirst()
                        .orElseThrow());
            }
            orderItemRepository.save(item);
        }

        if (snapshot == null) cartService.clear(userId);
        else cartService.consumeCheckoutCart(cart);

        // Same transaction as the Order/OrderItem writes above - see
        // KafkaPaymentEventPublisher: this only writes an outbox row here,
        // the actual Kafka send happens later, outside this transaction.
        paymentEventPublisher.publishPaymentRequested(new PaymentRequestedEvent(
                UUID.randomUUID(),
                saved.getId(),
                userId,
                totalAmount,
                DEFAULT_CURRENCY,
                Instant.now(),
                snapshot != null && "STRIPE".equals(snapshot.details().paymentSelection()) ? "stripe" : "simulated"));
        recordStatusEvent(saved, null, OrderStatus.PENDING, userId == null ? "GUEST" : "CUSTOMER", "Order placed");
        notifyOrder(saved, "PLACED");

        return saved;
    }

    public List<ShippingOption> shippingOptions(String countryCode) {
        String code = countryCode == null ? null : countryCode.trim().toUpperCase(java.util.Locale.ROOT);
        if (code != null
                && !java.util.Set.of(java.util.Locale.getISOCountries()).contains(code))
            throw new IllegalArgumentException("Country must be an ISO alpha-2 code");
        return deliveryOptionsFor(code, checkoutSettings.getObject());
    }

    private List<ShippingOption> deliveryOptionsFor(String countryCode, CheckoutSettings settings) {
        DeliveryOptionService service = deliveryOptions == null ? null : deliveryOptions.getIfAvailable();
        return service == null ? settings.options(countryCode) : service.checkoutOptions(countryCode);
    }

    @Transactional(readOnly = true)
    public Preview previewCheckout(Long userId, String guestCredential, Selection selection) {
        Cart cart = userId == null ? cartService.getGuestCart(guestCredential) : cartService.getMyCart(userId);
        if (cart == null) throw new ee.bytecore.backend.exceptions.GuestCartUnavailableException();
        return calculatePreview(cart, userId, CheckoutSupport.normalize(selection));
    }

    @Transactional
    public Order placeCheckout(Long userId, String guestCredential, String orderCredential, Placement input) {
        if (input == null || input.requestId() == null)
            throw new IllegalArgumentException("Checkout request UUID is required");
        if (input.acceptedQuoteVersion() == null
                || !input.acceptedQuoteVersion().matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Accepted checkout quote is required");
        Placement canonical = new Placement(
                input.requestId(), input.acceptedQuoteVersion(), CheckoutSupport.normalize(input.checkout()));
        String payloadHash = CheckoutSupport.fingerprint(canonical);
        long lockKey =
                input.requestId().getMostSignificantBits() ^ input.requestId().getLeastSignificantBits();
        checkoutJdbc.getObject().query("SELECT pg_advisory_xact_lock(?)", row -> {}, lockKey);
        if (userId != null) requireActiveCheckoutUser(userId);
        String orderHash = userId == null ? guestOrderHash(orderCredential) : null;
        var previous = checkoutRequests.getObject().findById(input.requestId());
        if (previous.isPresent()) {
            if (userId != null)
                checkoutUsers
                        .getObject()
                        .findActiveByIdForUpdate(userId)
                        .orElseThrow(() -> new AccessDeniedException("Active account is required"));
            CheckoutRequest receipt = previous.get();
            authorizeReceipt(receipt, userId, orderHash);
            if (!receipt.getPayloadHash().equals(payloadHash)
                    || (guestCredential != null
                            && userId == null
                            && !Objects.equals(
                                    receipt.getSourceCredentialHash(), GuestCartCredentials.hash(guestCredential))))
                throw new CheckoutException(
                        "CHECKOUT_REQUEST_CONFLICT",
                        "Checkout request UUID was used with different selections or cart");
            return orderRepository.findById(receipt.getOrder().getId()).orElseThrow();
        }
        Cart cart = userId == null
                ? cartService.getGuestCartForUpdate(guestCredential)
                : cartService.getMyCartForUpdate(userId);
        cart.getItems()
                .sort(Comparator.comparing(item -> item.getProductVariant().getId()));
        lockCheckoutCatalog(cart);
        lockSavedSelections(canonical.checkout());
        Preview preview = calculatePreview(cart, userId, canonical.checkout());
        if (!preview.placeable())
            throw new CheckoutException(
                    "CHECKOUT_INVALID", preview.issues().getFirst().message());
        if (!preview.quoteVersion().equals(input.acceptedQuoteVersion()))
            throw new CheckoutException(
                    "CHECKOUT_QUOTE_CHANGED",
                    "Checkout quote changed; preview again and explicitly accept the new quote");
        if ("STRIPE".equals(canonical.checkout().paymentSelection())
                && !stripePayments.getObject().configuration().available())
            throw new CheckoutException(
                    "PAYMENT_NOT_CONFIGURED", "Stripe sandbox is not configured; no order was placed");
        Snapshot snapshot = new Snapshot(1, input.requestId(), preview.lines(), preview.details(), preview.totals());
        Order order = persistOrder(cart, userId, snapshot);
        checkoutRequests
                .getObject()
                .saveAndFlush(CheckoutRequest.create(
                        input.requestId(),
                        userId,
                        cart.getId(),
                        userId == null ? GuestCartCredentials.hash(guestCredential) : null,
                        orderHash,
                        userId == null
                                ? Instant.now()
                                        .plus(checkoutSettings.getObject().getGuestOrderTtl())
                                : null,
                        payloadHash,
                        order));
        return order;
    }

    @Transactional(readOnly = true)
    public Confirmation checkoutOrder(UUID requestId, Long userId) {
        if (userId == null) throw new AccessDeniedException("Active account is required");
        requireActiveCheckoutUser(userId);
        var receipt = checkoutRequests.getObject().findById(requestId);
        if (receipt.isEmpty()) return null;
        authorizeReceipt(receipt.get(), userId, null);
        return confirmation(receipt.get().getOrder());
    }

    @Transactional(readOnly = true)
    public Confirmation guestOrder(UUID publicId, UUID requestId, String credential) {
        if ((publicId == null) == (requestId == null))
            throw new IllegalArgumentException("Provide exactly one order reference or request UUID");
        String hash = guestOrderHash(credential);
        CheckoutRequest receipt = (requestId != null
                        ? checkoutRequests.getObject().findById(requestId)
                        : checkoutRequests.getObject().findByOrderPublicId(publicId))
                .orElseThrow(CheckoutSupport::unavailable);
        authorizeReceipt(receipt, null, hash);
        return confirmation(receipt.getOrder());
    }

    public Confirmation confirmation(Order order) {
        Snapshot snapshot = order.getCheckoutSnapshot();
        if (snapshot == null) return null;
        return new Confirmation(
                order.getPublicId(),
                snapshot.requestId(),
                order.getStatus().name(),
                order.getCancellationReason(),
                snapshot.lines(),
                snapshot.details(),
                snapshot.totals(),
                order.getCreatedAt(),
                "STRIPE".equals(snapshot.details().paymentSelection()) ? "STRIPE_ELEMENTS" : "NONE",
                recovery(order));
    }

    @Transactional(readOnly = true)
    public ee.bytecore.backend.integration.payment.StripePaymentClient.Session paymentSession(
            UUID requestId, Long userId, String guestCredential) {
        Confirmation confirmation =
                userId == null ? guestOrder(null, requestId, guestCredential) : checkoutOrder(requestId, userId);
        if (confirmation == null || !"STRIPE".equals(confirmation.details().paymentSelection()))
            throw new CheckoutException("PAYMENT_UNAVAILABLE", "Stripe payment is unavailable");
        if (!"PENDING".equals(confirmation.status()))
            return new ee.bytecore.backend.integration.payment.StripePaymentClient.Session(
                    confirmation.recovery().payment().status(), "", null, null);
        var receipt = checkoutRequests.getObject().findById(requestId).orElseThrow();
        var requests = financialRequests
                .getObject()
                .findAllByOrderIdAndEventType(receipt.getOrder().getId(), "payment.requested");
        if (requests.size() != 1) throw new IllegalStateException("Original payment request requires reconciliation");
        try {
            var event = new com.fasterxml.jackson.databind.ObjectMapper()
                    .findAndRegisterModules()
                    .readValue(requests.getFirst().getPayload(), PaymentRequestedEvent.class);
            return stripePayments.getObject().session(event.eventId());
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Persisted payment request is malformed", exception);
        }
    }

    private OrderRecoveryValues.Summary recovery(Order order) {
        return OrderRecoveryValues.summary(
                order, refunds.getObject().findByOrderId(order.getId()).orElse(null));
    }

    @Transactional(readOnly = true)
    public OrderRecoveryValues.Summary recovery(Long id, Long userId, boolean staff) {
        Order order = orderRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Order not found"));
        requireReadable(order, userId, staff);
        return recovery(order);
    }

    @Transactional
    public OrderRecoveryValues.Payload cancelOrder(
            UUID requestId, UUID publicId, Long userId, String guestCredential, boolean staff) {
        if (requestId == null || publicId == null)
            throw new IllegalArgumentException("Cancellation request UUID and order reference are required");
        checkoutJdbc
                .getObject()
                .query(
                        "SELECT pg_advisory_xact_lock(?)",
                        row -> {},
                        requestId.getMostSignificantBits() ^ requestId.getLeastSignificantBits());
        String actor;
        if (userId != null) {
            checkoutUsers
                    .getObject()
                    .findActiveByIdForUpdate(userId)
                    .orElseThrow(() -> new AccessDeniedException("Active account is required"));
            actor = (staff ? "staff:" : "user:") + userId;
        } else {
            if (staff) throw new AccessDeniedException("Active staff account is required");
            actor = "guest:" + guestOrderHash(guestCredential);
        }
        Order found = orderRepository
                .findByPublicId(publicId)
                .orElseThrow(() -> userId == null
                        ? CheckoutSupport.unavailable()
                        : new EntityNotFoundException("Order not found"));
        Order order = lockForFinancialResult(found.getId());
        if (userId == null) {
            var receipt = checkoutRequests
                    .getObject()
                    .findByOrderPublicId(publicId)
                    .orElseThrow(CheckoutSupport::unavailable);
            authorizeReceipt(receipt, null, guestOrderHash(guestCredential));
        } else {
            requireReadable(order, userId, staff);
        }
        record CancellationReceipt(Long orderId, String actor) {}
        var previous = checkoutJdbc
                .getObject()
                .query(
                        "SELECT order_id,actor FROM order_cancellation_requests WHERE request_id=?",
                        (rs, row) -> new CancellationReceipt(rs.getLong("order_id"), rs.getString("actor")),
                        requestId);
        if (!previous.isEmpty()
                && (!previous.getFirst().orderId().equals(order.getId())
                        || !previous.getFirst().actor().equals(actor))) {
            throw new CheckoutException(
                    "CANCELLATION_REQUEST_CONFLICT", "Cancellation request UUID was used for another request");
        }
        if (order.getStatus() != OrderStatus.CANCELLED) {
            var eligibility = OrderRecoveryValues.eligibility(order);
            if (!eligibility.allowed()) throw new CheckoutException("ORDER_NOT_CANCELLABLE", eligibility.reason());
            order.setCancellationSource(staff ? "STAFF_REQUESTED" : "CUSTOMER_REQUESTED");
            OrderStatus before = order.getStatus();
            updateStatus(
                    order.getId(),
                    OrderStatus.CANCELLED,
                    staff ? "STAFF_REQUESTED" : "CUSTOMER_REQUESTED",
                    staff ? "STAFF" : userId == null ? "GUEST" : "CUSTOMER",
                    null);
            if (staff) audit("ORDER_CANCELLED", order, Map.of("from", before.name()));
        } else {
            ensureRefund(order);
        }
        if (previous.isEmpty()) {
            checkoutJdbc
                    .getObject()
                    .update(
                            "INSERT INTO order_cancellation_requests(request_id,order_id,actor) VALUES (?,?,?)",
                            requestId,
                            order.getId(),
                            actor);
        }
        var summary = recovery(order);
        return new OrderRecoveryValues.Payload(
                requestId,
                new OrderRecoveryValues.SummaryOrder(
                        order.getPublicId(),
                        order.getStatus().name(),
                        summary.cancellationEligibility(),
                        summary.cancellation(),
                        summary.payment(),
                        summary.refund()));
    }

    @Transactional
    public Order updateFulfillmentStatus(Long id, OrderStatus target, Long actorId) {
        return updateFulfillmentStatus(id, target, actorId, null);
    }

    /**
     * Staff fulfillment: PAID -> PROCESSING -> SHIPPING -> COMPLETED, one step at a time. Shipping a
     * delivery (non-pickup) checkout order requires tracking to be set first.
     */
    @Transactional
    public Order updateFulfillmentStatus(Long id, OrderStatus target, Long actorId, String note) {
        if (target != OrderStatus.PROCESSING && target != OrderStatus.SHIPPING && target != OrderStatus.COMPLETED)
            throw new IllegalArgumentException(
                    "Staff may set PROCESSING/SHIPPING/COMPLETED only; use cancelOrderAsStaff for cancellation");
        String cleanNote = cleanText(note, 0, 500, "note");
        checkoutUsers
                .getObject()
                .findActiveByIdForUpdate(actorId)
                .orElseThrow(() -> new AccessDeniedException("Active account is required"));
        Order order = lockForFinancialResult(id);
        if (!"SUCCEEDED".equals(order.getPaymentStatus()))
            throw new IllegalArgumentException("Fulfillment requires a confirmed successful payment");
        if (target == OrderStatus.SHIPPING
                && order.getCheckoutSnapshot() != null
                && !"PICKUP"
                        .equals(order.getCheckoutSnapshot().details().shipping().method())
                && order.getTrackingNumber() == null)
            throw new CheckoutException("TRACKING_REQUIRED", "Set carrier and tracking number before shipping");
        OrderStatus before = order.getStatus();
        Order saved = updateStatus(id, target, null, "STAFF", cleanNote);
        audit("ORDER_STATUS_UPDATED", saved, Map.of("from", before.name(), "to", target.name()));
        return saved;
    }

    private static final java.util.regex.Pattern CARRIER = java.util.regex.Pattern.compile("[\\p{L}\\p{N} .&'-]{2,80}");
    private static final java.util.regex.Pattern TRACKING_NUMBER =
            java.util.regex.Pattern.compile("[A-Za-z0-9-]{4,64}");

    @Transactional
    public Order setTracking(UUID publicId, String carrier, String trackingNumber, String trackingUrl, Long actorId) {
        String c = carrier == null ? "" : carrier.trim();
        String n = trackingNumber == null ? "" : trackingNumber.trim();
        if (!CARRIER.matcher(c).matches())
            throw new CheckoutException("TRACKING_INVALID", "Carrier must be 2-80 letters, digits or . & ' -");
        if (!TRACKING_NUMBER.matcher(n).matches())
            throw new CheckoutException("TRACKING_INVALID", "Tracking number must be 4-64 letters, digits or -");
        String url = validTrackingUrl(trackingUrl);
        checkoutUsers
                .getObject()
                .findActiveByIdForUpdate(actorId)
                .orElseThrow(() -> new AccessDeniedException("Active account is required"));
        Order found = orderRepository
                .findByPublicId(publicId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        Order order = lockForFinancialResult(found.getId());
        if (!java.util.Set.of(OrderStatus.PAID, OrderStatus.PROCESSING, OrderStatus.SHIPPING, OrderStatus.COMPLETED)
                .contains(order.getStatus()))
            throw new CheckoutException("TRACKING_NOT_ALLOWED", "Tracking can only be set on paid orders");
        order.setCarrier(c);
        order.setTrackingNumber(n);
        order.setTrackingUrl(url);
        Order saved = orderRepository.saveAndFlush(order);
        audit("ORDER_TRACKING_SET", saved, Map.of("carrier", c, "trackingNumber", n, "hasUrl", url != null));
        return saved;
    }

    static String validTrackingUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        if (value.length() > 500) throw new CheckoutException("TRACKING_INVALID", "Tracking URL is too long");
        try {
            var uri = new java.net.URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http")
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getRawUserInfo() != null)
                throw new CheckoutException("TRACKING_INVALID", "Tracking URL must be an http(s) URL");
            return uri.toString();
        } catch (java.net.URISyntaxException exception) {
            throw new CheckoutException("TRACKING_INVALID", "Tracking URL must be an http(s) URL");
        }
    }

    private static String cleanText(String raw, int min, int max, String field) {
        if (raw == null) {
            if (min > 0) throw new IllegalArgumentException(field + " is required");
            return null;
        }
        String value = raw.replaceAll("[\\p{Cntrl}&&[^\\n]]", "").trim();
        if (value.isEmpty() && min == 0) return null;
        if (value.length() < min || value.length() > max)
            throw new IllegalArgumentException(field + " must be " + min + "-" + max + " characters");
        return value;
    }

    /** Customer refund request on an own paid order; idempotent while a request is open. */
    @Transactional
    public RefundRequest requestRefund(UUID publicId, String reason, Long userId) {
        if (userId == null) throw new AccessDeniedException("Active account is required");
        String cleanReason = cleanText(reason, 3, 1000, "reason");
        checkoutUsers
                .getObject()
                .findActiveByIdForUpdate(userId)
                .orElseThrow(() -> new AccessDeniedException("Active account is required"));
        Order found = orderRepository
                .findByPublicId(publicId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        requireReadable(found, userId, false);
        Order order = lockForFinancialResult(found.getId());
        var open = refundRequests.getObject().findFirstByOrderIdAndStatus(order.getId(), "REQUESTED");
        if (open.isPresent()) return open.get();
        var eligibility = refundEligibility(
                order, refunds.getObject().findByOrderId(order.getId()).isPresent(), false);
        if (!eligibility.allowed()) throw new CheckoutException("REFUND_NOT_ALLOWED", eligibility.reason());
        RefundRequest request = refundRequests
                .getObject()
                .saveAndFlush(RefundRequest.open(order.getId(), "user:" + userId, cleanReason));
        notifyOrder(order, "REFUND_REQUESTED", request.getId().toString(), null);
        return request;
    }

    @Transactional
    public RefundRequest approveRefund(UUID requestId, String note, Long actorId) {
        String cleanNote = cleanText(note, 0, 1000, "note");
        var decision = lockRequestForDecision(requestId, actorId);
        RefundRequest request = decision.request();
        if ("APPROVED".equals(request.getStatus())) return request;
        if (!"REQUESTED".equals(request.getStatus()))
            throw new CheckoutException("REFUND_REQUEST_DECIDED", "Refund request was already decided");
        Order order = decision.order();
        var eligibility = refundEligibility(
                order, refunds.getObject().findByOrderId(order.getId()).isPresent(), false);
        if (!eligibility.allowed()) throw new CheckoutException("REFUND_NOT_ALLOWED", eligibility.reason());
        // Approval refunds the payment only; fulfilled goods are not restocked automatically.
        OrderRefund refund = issueRefund(order);
        decide(request, "APPROVED", "staff:" + actorId, cleanNote, refund.getId());
        audit(
                "ORDER_REFUND_APPROVED",
                order,
                Map.of(
                        "refundRequestId",
                        request.getId().toString(),
                        "refundId",
                        refund.getId().toString()));
        return request;
    }

    @Transactional
    public RefundRequest rejectRefund(UUID requestId, String note, Long actorId) {
        String cleanNote = cleanText(note, 3, 1000, "note");
        var decision = lockRequestForDecision(requestId, actorId);
        RefundRequest request = decision.request();
        if ("REJECTED".equals(request.getStatus())) return request;
        if (!"REQUESTED".equals(request.getStatus()))
            throw new CheckoutException("REFUND_REQUEST_DECIDED", "Refund request was already decided");
        decide(request, "REJECTED", "staff:" + actorId, cleanNote, null);
        notifyOrder(decision.order(), "REFUND_REJECTED", request.getId().toString(), cleanNote);
        audit(
                "ORDER_REFUND_REJECTED",
                decision.order(),
                Map.of("refundRequestId", request.getId().toString()));
        return request;
    }

    private record Decision(Order order, RefundRequest request) {}

    private Decision lockRequestForDecision(UUID requestId, Long actorId) {
        if (requestId == null) throw new IllegalArgumentException("Refund request id is required");
        checkoutUsers
                .getObject()
                .findActiveByIdForUpdate(actorId)
                .orElseThrow(() -> new AccessDeniedException("Active account is required"));
        RefundRequest found = refundRequests
                .getObject()
                .findById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Refund request not found"));
        Order order = lockForFinancialResult(found.getOrderId());
        EntityManager em = checkoutEntityManager.getIfAvailable();
        if (em != null) em.refresh(found);
        return new Decision(order, found);
    }

    private void decide(RefundRequest request, String status, String actor, String note, UUID refundId) {
        request.setStatus(status);
        request.setDecidedBy(actor);
        request.setDecisionNote(note);
        request.setDecidedAt(Instant.now());
        request.setRefundId(refundId);
        refundRequests.getObject().saveAndFlush(request);
    }

    @Transactional
    public Order lockForFinancialResult(Long id) {
        Order order =
                orderRepository.findByIdForUpdate(id).orElseThrow(() -> new EntityNotFoundException("Order not found"));
        EntityManager em = checkoutEntityManager.getIfAvailable();
        if (em != null) em.refresh(order);
        return order;
    }

    public void applyPaymentOutcome(
            Order order,
            PaymentRequestedEvent request,
            UUID paymentId,
            String status,
            String reason,
            String providerTransactionId) {
        if (order.getPaymentId() != null && !order.getPaymentId().equals(paymentId))
            throw new IllegalArgumentException("Payment identity conflicts with the observed original payment");
        if (java.util.Set.of("SUCCEEDED", "FAILED").contains(order.getPaymentStatus())) {
            if (!order.getPaymentStatus().equals(status) && !"UNRESOLVED".equals(status))
                throw new IllegalArgumentException("Payment outcome conflicts with the terminal outcome");
            return;
        }
        order.setPaymentId(paymentId);
        order.setPaymentStatus(status);
        if ("UNRESOLVED".equals(status)) {
            orderRepository.save(order);
            if ("payment_retry_required".equals(reason)) notifyOrder(order, "PAYMENT_RETRY_REQUIRED");
            return;
        }
        order.setProviderTransactionId(providerTransactionId);
        if (order.getStatus() == OrderStatus.CANCELLED) {
            if ("SUCCEEDED".equals(status)) ensureRefund(order);
        } else if ("SUCCEEDED".equals(status)) {
            updateStatus(order.getId(), OrderStatus.PAID, null, "PAYMENT", null);
        } else {
            order.setCancellationSource("PAYMENT_FAILED");
            updateStatus(order.getId(), OrderStatus.CANCELLED, "PAYMENT_FAILED: " + reason, "PAYMENT", null);
        }
        orderRepository.save(order);
    }

    /**
     * Records one definitely declined attempt (idempotent per payment+attempt). The order stays
     * PENDING and keeps its stock reservation so the customer can retry on the same order and
     * PaymentIntent; decline is never terminal and never touches inventory.
     */
    public void applyPaymentDecline(Order order, UUID paymentId, UUID eventId, String attemptId, String reason) {
        if (order.getPaymentId() != null && !order.getPaymentId().equals(paymentId))
            throw new IllegalArgumentException("Payment identity conflicts with the observed original payment");
        if (java.util.Set.of("SUCCEEDED", "FAILED").contains(order.getPaymentStatus())) return;
        String code = DECLINE_REASONS.contains(reason) ? reason : "card_declined";
        int inserted = checkoutJdbc
                .getObject()
                .update(
                        """
                        INSERT INTO order_payment_declines(payment_id,attempt_id,order_id,reason,event_id)
                        VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING
                        """,
                        paymentId,
                        attemptId,
                        order.getId(),
                        code,
                        eventId);
        if (inserted == 0) return;
        order.setPaymentId(paymentId);
        order.setPaymentStatus("DECLINED");
        order.setPaymentDeclineReason(code);
        orderRepository.save(order);
        if (order.getStatus() == OrderStatus.PENDING) notifyOrder(order, "PAYMENT_FAILED", attemptId, code);
    }

    /** Allow-listed decline reason codes shared with the Payment Service and the storefront. */
    public static final java.util.Set<String> DECLINE_REASONS = java.util.Set.of(
            "insufficient_funds",
            "expired_card",
            "incorrect_cvc",
            "incorrect_number",
            "invalid_expiry",
            "card_not_supported",
            "authentication_failed",
            "card_declined");

    private void ensureRefund(Order order) {
        if (!"SUCCEEDED".equals(order.getPaymentStatus()) || order.getStatus() != OrderStatus.CANCELLED) return;
        if (refunds.getObject().findByOrderId(order.getId()).isPresent()) return;
        OrderRefund refund = issueRefund(order);
        // A paid order cancelled while a customer refund request was open is refunded by the
        // cancellation; close the request against that refund instead of leaving it dangling.
        var requests = refundRequests == null ? null : refundRequests.getIfAvailable();
        if (requests != null)
            requests.findFirstByOrderIdAndStatus(order.getId(), "REQUESTED")
                    .ifPresent(open -> decide(
                            open, "APPROVED", "system:cancellation", "Refunded by cancellation", refund.getId()));
    }

    /**
     * Writes the single order_refunds row plus the refund.requested outbox event for the full charged
     * amount. Shared by paid-order cancellation and staff-approved refund requests; never restocks.
     */
    private OrderRefund issueRefund(Order order) {
        var requests = financialRequests.getObject().findAllByOrderIdAndEventType(order.getId(), "payment.requested");
        if (requests.size() != 1)
            throw new IllegalArgumentException("Original payment request requires reconciliation");
        PaymentRequestedEvent request;
        try {
            request = new com.fasterxml.jackson.databind.ObjectMapper()
                    .findAndRegisterModules()
                    .readValue(requests.getFirst().getPayload(), PaymentRequestedEvent.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Persisted original payment request is malformed", exception);
        }
        var receipt = financialRequests
                .getObject()
                .findResultByRequestEventId(request.eventId())
                .orElseThrow(() -> new IllegalArgumentException("Successful payment receipt requires reconciliation"));
        if (!"PAID".equals(receipt.getResultStatus())
                || !Objects.equals(receipt.getPaymentId(), order.getPaymentId())
                || !Objects.equals(request.orderId(), order.getId())
                || request.amount().compareTo(order.getTotalAmount()) != 0)
            throw new IllegalArgumentException("Original charged payment facts conflict");
        OrderRefund refund = new OrderRefund();
        refund.setId(UUID.randomUUID());
        refund.setRequestEventId(UUID.randomUUID());
        refund.setPaymentRequestEventId(request.eventId());
        refund.setOrderId(order.getId());
        refund.setPaymentId(order.getPaymentId());
        refund.setAmount(request.amount());
        refund.setCurrency(request.currency());
        refund.setProviderTransactionId(order.getProviderTransactionId());
        refund.setStatus("PENDING");
        refunds.getObject().saveAndFlush(refund);
        paymentEventPublisher.publishRefundRequested(new RefundRequestedEvent(
                refund.getRequestEventId(),
                refund.getId(),
                request.eventId(),
                order.getId(),
                order.getPaymentId(),
                request.amount(),
                request.currency(),
                order.getProviderTransactionId(),
                Instant.now()));
        return refund;
    }

    private void requireActiveCheckoutUser(Long userId) {
        checkoutUsers
                .getObject()
                .findById(userId)
                .orElseThrow(() -> new AccessDeniedException("Active account is required"));
    }

    private String guestOrderHash(String credential) {
        if (credential == null || !credential.matches("[A-Za-z0-9_-]{43}")) throw CheckoutSupport.unavailable();
        return GuestCartCredentials.hash(credential);
    }

    private void authorizeReceipt(CheckoutRequest receipt, Long userId, String guestHash) {
        if (userId != null) {
            if (!Objects.equals(receipt.getUserId(), userId))
                throw new AccessDeniedException("Checkout does not belong to the current user");
        } else if (receipt.getUserId() != null
                || guestHash == null
                || !guestHash.equals(receipt.getGuestOrderHash())
                || !receipt.getGuestExpiresAt().isAfter(Instant.now())) {
            throw CheckoutSupport.unavailable();
        }
    }

    private void lockCheckoutCatalog(Cart cart) {
        JdbcTemplate jdbc = checkoutJdbc.getObject();
        var variants = cart.getItems().stream().map(CartItem::getProductVariant).toList();
        variants.stream()
                .map(variant -> variant.getProduct().getId())
                .distinct()
                .sorted()
                .forEach(id -> jdbc.query("SELECT id FROM products WHERE id=? FOR SHARE", row -> {}, id));
        variants.stream()
                .map(ProductVariant::getId)
                .distinct()
                .sorted()
                .forEach(id -> jdbc.query("SELECT id FROM product_variants WHERE id=? FOR SHARE", row -> {}, id));
        EntityManager em = checkoutEntityManager.getObject();
        variants.forEach(variant -> {
            em.refresh(variant);
            em.refresh(variant.getProduct());
        });
    }

    private Preview calculatePreview(Cart cart, Long userId, Selection selection) {
        CheckoutSettings settings = checkoutSettings.getObject();
        String email = selection.contactEmail();
        if (email == null && userId != null) email = cart.getUser().getEmail();
        email = CheckoutSupport.email(email);
        if (!java.util.Set.of("SIMULATED", "STRIPE").contains(selection.paymentSelection()))
            throw new IllegalArgumentException("Unsupported payment selection");
        if ("STRIPE".equals(selection.paymentSelection()) && selection.paymentMethodId() != null)
            throw new IllegalArgumentException("Stripe payment details must be collected through hosted fields");
        Address shipping = resolveAddress(userId, selection.shippingAddressId(), selection.shippingAddress());
        Address billing = resolveAddress(userId, selection.billingAddressId(), selection.billingAddress());
        boolean pickup = "PICKUP".equals(selection.shippingMethod());
        if (pickup && shipping != null)
            throw new IllegalArgumentException("Pickup must not include a shipping address");
        if (!pickup && shipping == null) throw new IllegalArgumentException("Delivery requires a shipping address");
        if (selection.billingSameAsShipping()) {
            if (billing != null || shipping == null)
                throw new IllegalArgumentException(
                        "Billing same as shipping requires delivery and no separate billing address");
            billing = shipping;
        }

        for (Address address : new Address[] {shipping, billing}) {
            if (address != null && !settings.getSupportedCountries().contains(address.countryCode()))
                throw new IllegalArgumentException("Address country is not supported");
        }
        String methodType = null;
        if (selection.paymentMethodId() != null) {
            if (userId == null) throw new AccessDeniedException("Guests cannot use saved payment methods");
            var method = checkoutMethods
                    .getObject()
                    .findById(selection.paymentMethodId())
                    .filter(value -> value.getUser() != null
                            && Objects.equals(userId, value.getUser().getId()))
                    .orElseThrow(() -> new AccessDeniedException("Payment method is unavailable"));
            if (!"simulated".equalsIgnoreCase(method.getProvider())
                    || method.getType() == ee.bytecore.backend.enums.PaymentMethodType.CASH_ON_DELIVERY)
                throw new IllegalArgumentException(
                        "Saved payment method is not compatible with the simulated provider");
            methodType = method.getType().name();
        }
        List<ShippingOption> options = deliveryOptionsFor(shipping == null ? null : shipping.countryCode(), settings);
        ShippingOption option = options.stream()
                .filter(value -> value.method().equals(selection.shippingMethod()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Shipping method is unsupported"));
        Details details = new Details(
                email, selection.contactPhone(), shipping, billing, option, selection.paymentSelection(), methodType);
        List<Line> lines = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        if (cart.getItems().isEmpty())
            issues.add(new Issue("EMPTY_CART", null, "Cannot create an order from an empty cart"));
        for (CartItem item : cart.getItems().stream()
                .sorted(Comparator.comparing(value -> value.getProductVariant().getId()))
                .toList()) {
            ProductVariant variant = item.getProductVariant();
            if (item.getQuantity() == null || item.getQuantity() <= 0)
                throw new IllegalArgumentException("Cart quantity must be positive");
            if (!Boolean.TRUE.equals(variant.getIsActive()))
                issues.add(new Issue("VARIANT_UNAVAILABLE", variant.getId(), "Product variant is unavailable"));
            int available = inventoryService.findAllByProductVariantId(variant.getId()).stream()
                    .mapToInt(Inventory::getQuantity)
                    .max()
                    .orElse(0);
            if (available < item.getQuantity())
                issues.add(new Issue("INSUFFICIENT_STOCK", variant.getId(), "Insufficient single-warehouse stock"));
            BigDecimal unit = CheckoutSupport.amount(variant.getPrice());
            lines.add(new Line(
                    variant.getId(),
                    variant.getProduct().getName(),
                    variant.getSku(),
                    CheckoutSupport.copyAttributes(variant.getAttributes()),
                    item.getQuantity(),
                    unit,
                    CheckoutSupport.amount(unit.multiply(BigDecimal.valueOf(item.getQuantity())))));
        }
        BigDecimal subtotal = CheckoutSupport.amount(
                lines.stream().map(Line::subtotal).reduce(new BigDecimal("0.00"), BigDecimal::add));
        Totals totals =
                new Totals(subtotal, option.charge(), CheckoutSupport.amount(subtotal.add(option.charge())), "EUR");
        String quote = CheckoutSupport.fingerprint(List.of(1, cart.getId(), lines, details, totals));
        return new Preview(quote, issues.isEmpty(), List.copyOf(lines), details, totals, List.copyOf(issues), options);
    }

    private void lockSavedSelections(Selection selection) {
        java.util.stream.Stream.of(selection.shippingAddressId(), selection.billingAddressId())
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .forEach(id -> checkoutJdbc
                        .getObject()
                        .query("SELECT id FROM user_address WHERE id=? FOR SHARE", row -> {}, id));
        if (selection.paymentMethodId() != null)
            checkoutJdbc
                    .getObject()
                    .query(
                            "SELECT id FROM user_payment_methods WHERE id=? FOR SHARE",
                            row -> {},
                            selection.paymentMethodId());
    }

    private Address resolveAddress(Long userId, Long addressId, Address inline) {
        if (addressId != null && inline != null)
            throw new IllegalArgumentException("Choose a saved address or an inline address, not both");
        if (addressId == null) return inline;
        if (userId == null) throw new AccessDeniedException("Guests cannot use saved addresses");
        UserAddress address = checkoutAddresses.getObject().findOwned(userId, addressId);
        return CheckoutSupport.normalize(new Address(
                address.getFirstName(),
                address.getLastName(),
                address.getCity(),
                address.getCountry(),
                address.getPostalCode(),
                address.getAddressLine1(),
                address.getAddressLine2(),
                address.getMobile()));
    }

    /**
     * Requires either ownership of the order or staff-level access; enforced
     * here (data authorization) rather than via @PreAuthorize (method
     * authorization can't express "unless it's your own resource").
     */
    public Order requireReadable(Order order, Long currentUserId, boolean isStaff) {
        if (!isStaff
                && order.getUser() != null
                && (order.getUser().isDeleted() || order.getUser().getDeletionIdentityId() != null)) {
            throw new AccessDeniedException("Active account is required");
        }
        Long ownerId = order.getUser() == null ? null : order.getUser().getId();
        if (!isStaff && (currentUserId == null || ownerId == null || !Objects.equals(currentUserId, ownerId))) {
            throw new AccessDeniedException("Order does not belong to the current user");
        }
        return order;
    }

    @Transactional
    public Order updateStatus(Long id, OrderStatus status) {
        return updateStatus(id, status, null);
    }

    /**
     * @param cancellationReason only meaningful when {@code status ==
     *     CANCELLED}; distinguishes an automatic payment-failure
     *     cancellation from a customer/staff cancellation. The public staff
     *     status mutation handles fulfillment only. Ignored for every other status.
     */
    @Transactional
    public Order updateStatus(Long id, OrderStatus status, String cancellationReason) {
        return updateStatus(id, status, cancellationReason, "SYSTEM", null);
    }

    /** Applies a validated transition and appends it to the order status history. */
    @Transactional
    public Order updateStatus(Long id, OrderStatus status, String cancellationReason, String actorKind, String note) {
        Order order = orderRepository
                .findByIdForUpdate(id)
                .orElseThrow(() -> new EntityNotFoundException(String.format("Order with id %s not found", id)));

        OrderStatus currentStatus = order.getStatus();
        if (!OrderStatusTransitions.canTransition(currentStatus, status)) {
            throw new IllegalArgumentException(
                    String.format("Cannot transition order %s from %s to %s", id, currentStatus, status));
        }

        if (status == OrderStatus.CANCELLED) {
            if (order.getInventoryReleasedAt() == null) {
                var items = orderItemRepository.findAllByOrderId(id).stream()
                        .sorted(Comparator.comparing((OrderItem item) ->
                                        item.getInventory().getProductVariant().getId())
                                .thenComparing(item -> item.getInventory().getId()))
                        .toList();
                for (OrderItem item : items) {
                    inventoryService.restore(item.getInventory().getId(), item.getQuantity());
                }
                order.setInventoryReleasedAt(Instant.now());
            }
            order.setCancellationReason(cancellationReason);
            order.setCancelledAt(Instant.now());
            if (order.getCancellationSource() == null)
                order.setCancellationSource(
                        cancellationReason != null && cancellationReason.startsWith("PAYMENT_FAILED:")
                                ? "PAYMENT_FAILED"
                                : "STAFF_REQUESTED");
        }

        if (status == OrderStatus.SHIPPING) order.setShippedAt(Instant.now());
        order.setStatus(status);
        if (status == OrderStatus.CANCELLED) ensureRefund(order);
        Order saved = orderRepository.save(order);
        recordStatusEvent(
                saved,
                currentStatus,
                status,
                actorKind,
                note != null
                        ? note
                        : status == OrderStatus.CANCELLED && cancellationReason != null
                                ? cancellationReason.substring(0, Math.min(500, cancellationReason.length()))
                                : null);
        switch (status) {
            case PAID -> notifyOrder(saved, "PAID");
            case PROCESSING -> notifyOrder(saved, "PROCESSING");
            case SHIPPING -> notifyOrder(saved, "SHIPPED");
            case COMPLETED -> notifyOrder(saved, "COMPLETED");
            case CANCELLED -> notifyOrder(saved, "CANCELLED");
            default -> {}
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    orderStatusPublisher.publish(saved);
                }
            });
        } else {
            orderStatusPublisher.publish(saved);
        }
        return saved;
    }
}
