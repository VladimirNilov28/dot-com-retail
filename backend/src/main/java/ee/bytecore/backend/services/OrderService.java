package ee.bytecore.backend.services;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ee.bytecore.backend.entities.cart.Cart;
import ee.bytecore.backend.entities.cart.CartItem;
import ee.bytecore.backend.entities.inventory.Inventory;
import ee.bytecore.backend.entities.payment.Order;
import ee.bytecore.backend.entities.payment.OrderItem;
import ee.bytecore.backend.enums.OrderStatus;
import ee.bytecore.backend.integration.payment.PaymentEventPublisher;
import ee.bytecore.backend.integration.payment.event.PaymentRequestedEvent;
import ee.bytecore.backend.repositories.payment.OrderItemRepository;
import ee.bytecore.backend.repositories.payment.OrderRepository;

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
        return orderRepository.findAllByUserId(userId);
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
        List<CartItem> cartItems = cart.getItems();
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

        Order order = Order.create(cart.getUser(), OrderStatus.PENDING, totalAmount);
        Order saved = orderRepository.save(order);

        for (CartItem cartItem : cartItems) {
            orderItemRepository.save(OrderItem.create(
                    saved,
                    cartItem.getProductVariant(),
                    allocations.get(cartItem),
                    cartItem.getQuantity(),
                    cartItem.getProductVariant().getPrice()));
        }

        cartService.clear(userId);

        // Same transaction as the Order/OrderItem writes above - see
        // KafkaPaymentEventPublisher: this only writes an outbox row here,
        // the actual Kafka send happens later, outside this transaction.
        paymentEventPublisher.publishPaymentRequested(new PaymentRequestedEvent(
                UUID.randomUUID(), saved.getId(), userId, totalAmount, DEFAULT_CURRENCY, Instant.now()));

        return saved;
    }

    /**
     * Requires either ownership of the order or staff-level access; enforced
     * here (data authorization) rather than via @PreAuthorize (method
     * authorization can't express "unless it's your own resource").
     */
    public Order requireReadable(Order order, Long currentUserId, boolean isStaff) {
        Long ownerId = order.getUser() == null ? null : order.getUser().getId();
        if (!isStaff && !Objects.equals(currentUserId, ownerId)) {
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
     *     cancellation from a manual one (e.g. via the admin
     *     {@code updateOrderStatus} mutation, which always passes {@code
     *     null} here). Ignored for every other status.
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
            for (OrderItem item : orderItemRepository.findAllByOrderId(id)) {
                inventoryService.restore(item.getInventory().getId(), item.getQuantity());
            }
            order.setCancellationReason(cancellationReason);
        }

        order.setStatus(status);
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
