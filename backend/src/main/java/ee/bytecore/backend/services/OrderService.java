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
import ee.bytecore.backend.repositories.payment.PaymentOutboxEventRepository;
import ee.bytecore.backend.repositories.user.UserPaymentMethodRepository;
import ee.bytecore.backend.repositories.user.UserRepository;
import ee.bytecore.backend.security.GuestCartCredentials;
import ee.bytecore.backend.services.CheckoutValues.*;

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
                UUID.randomUUID(), saved.getId(), userId, totalAmount, DEFAULT_CURRENCY, Instant.now()));

        return saved;
    }

    public List<ShippingOption> shippingOptions(String countryCode) {
        String code = countryCode == null ? null : countryCode.trim().toUpperCase(java.util.Locale.ROOT);
        if (code != null
                && !java.util.Set.of(java.util.Locale.getISOCountries()).contains(code))
            throw new IllegalArgumentException("Country must be an ISO alpha-2 code");
        return checkoutSettings.getObject().options(code);
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
                "NONE",
                recovery(order));
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
            updateStatus(order.getId(), OrderStatus.CANCELLED, staff ? "STAFF_REQUESTED" : "CUSTOMER_REQUESTED");
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
        if (target != OrderStatus.SHIPPING && target != OrderStatus.COMPLETED)
            throw new IllegalArgumentException(
                    "Staff may set SHIPPING/COMPLETED only; use cancelOrderAsStaff for cancellation");
        checkoutUsers
                .getObject()
                .findActiveByIdForUpdate(actorId)
                .orElseThrow(() -> new AccessDeniedException("Active account is required"));
        Order order = lockForFinancialResult(id);
        if (!"SUCCEEDED".equals(order.getPaymentStatus()))
            throw new IllegalArgumentException("Fulfillment requires a confirmed successful payment");
        return updateStatus(id, target);
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
            return;
        }
        order.setProviderTransactionId(providerTransactionId);
        if (order.getStatus() == OrderStatus.CANCELLED) {
            if ("SUCCEEDED".equals(status)) ensureRefund(order);
        } else if ("SUCCEEDED".equals(status)) {
            updateStatus(order.getId(), OrderStatus.PAID);
        } else {
            order.setCancellationSource("PAYMENT_FAILED");
            updateStatus(order.getId(), OrderStatus.CANCELLED, "PAYMENT_FAILED: " + reason);
        }
        orderRepository.save(order);
    }

    private void ensureRefund(Order order) {
        if (!"SUCCEEDED".equals(order.getPaymentStatus()) || order.getStatus() != OrderStatus.CANCELLED) return;
        if (refunds.getObject().findByOrderId(order.getId()).isPresent()) return;
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
        if (!"SIMULATED".equals(selection.paymentSelection()))
            throw new IllegalArgumentException("Only SIMULATED payment is supported");
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
        List<ShippingOption> options = settings.options(shipping == null ? null : shipping.countryCode());
        ShippingOption option = options.stream()
                .filter(value -> value.method().equals(selection.shippingMethod()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Shipping method is unsupported"));
        Details details =
                new Details(email, selection.contactPhone(), shipping, billing, option, "SIMULATED", methodType);
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

        order.setStatus(status);
        if (status == OrderStatus.CANCELLED) ensureRefund(order);
        Order saved = orderRepository.save(order);
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
