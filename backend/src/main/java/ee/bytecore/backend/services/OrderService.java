package ee.bytecore.backend.services;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.entities.cart.Cart;
import ee.bytecore.backend.entities.cart.CartItem;
import ee.bytecore.backend.entities.payment.Order;
import ee.bytecore.backend.entities.payment.OrderItem;
import ee.bytecore.backend.enums.OrderStatus;
import ee.bytecore.backend.repositories.payment.OrderItemRepository;
import ee.bytecore.backend.repositories.payment.OrderRepository;

import jakarta.persistence.EntityNotFoundException;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CartService cartService;
    private final OrderStatusPublisher orderStatusPublisher;

    public OrderService(
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            CartService cartService,
            OrderStatusPublisher orderStatusPublisher) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.cartService = cartService;
        this.orderStatusPublisher = orderStatusPublisher;
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

    @Transactional
    public Order createOrder(Long userId) {
        Cart cart = cartService.getMyCart(userId);
        List<CartItem> cartItems = cart.getItems();
        if (cartItems.isEmpty()) {
            throw new IllegalArgumentException("Cannot create an order from an empty cart");
        }

        BigDecimal totalAmount = BigDecimal.ZERO;
        for (CartItem cartItem : cartItems) {
            totalAmount = totalAmount.add(
                    cartItem.getProductVariant().getPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity())));
        }

        Order order = Order.create(cart.getUser(), OrderStatus.PENDING, totalAmount);
        Order saved = orderRepository.save(order);

        for (CartItem cartItem : cartItems) {
            orderItemRepository.save(OrderItem.create(
                    saved,
                    cartItem.getProductVariant(),
                    cartItem.getQuantity(),
                    cartItem.getProductVariant().getPrice()));
        }

        cartService.clear(userId);
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
        Order order = orderRepository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(String.format("Order with id %s not found", id)));
        order.setStatus(status);
        Order saved = orderRepository.save(order);
        orderStatusPublisher.publish(saved);
        return saved;
    }
}
