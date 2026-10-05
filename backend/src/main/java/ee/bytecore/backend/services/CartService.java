package ee.bytecore.backend.services;

import java.util.Objects;

import org.springframework.context.annotation.Lazy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.entities.cart.Cart;
import ee.bytecore.backend.entities.cart.CartItem;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.repositories.cart.CartItemRepository;
import ee.bytecore.backend.repositories.cart.CartRepository;
import ee.bytecore.backend.repositories.product.ProductVariantRepository;
import ee.bytecore.backend.repositories.user.UserRepository;

import jakarta.persistence.EntityNotFoundException;

@Service
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductVariantRepository productVariantRepository;
    private final UserRepository userRepository;

    /**
     * Self-injected proxy used purely so {@link #createCart(Long)} can run in
     * its own new transaction (see {@link #getMyCart(Long)}) - a plain
     * self-invocation would bypass Spring's transactional proxy entirely.
     */
    private final CartService self;

    public CartService(
            CartRepository cartRepository,
            CartItemRepository cartItemRepository,
            ProductVariantRepository productVariantRepository,
            UserRepository userRepository,
            @Lazy CartService self) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.productVariantRepository = productVariantRepository;
        this.userRepository = userRepository;
        this.self = self;
    }

    /**
     * Returns the user's cart, lazily creating it on first access. A missing
     * cart is created in its own {@code REQUIRES_NEW} transaction so that,
     * serialized creation can commit before the outer checkout acquires its
     * user/cart locks. Creation locks and validates the account, then rechecks
     * for a cart created by another request.
     */
    @Transactional(readOnly = true)
    public Cart getMyCart(Long userId) {
        Cart cart = cartRepository.findByUserId(userId).orElseGet(() -> self.createCart(userId));
        requireActiveOwner(cart.getUser());
        return cart;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Cart createCart(Long userId) {
        User user = userRepository
                .findActiveByIdForUpdate(userId)
                .orElseThrow(() -> new AccessDeniedException("Active account is required"));
        requireActiveOwner(user);
        return cartRepository.findByUserId(userId).orElseGet(() -> cartRepository.saveAndFlush(Cart.create(user)));
    }

    @Transactional
    public Cart getMyCartForUpdate(Long userId) {
        // Creation has its own transaction; do not hold the same user lock
        // in the outer transaction while entering that REQUIRES_NEW call.
        cartRepository.findByUserId(userId).orElseGet(() -> self.createCart(userId));
        User user = userRepository
                .findActiveByIdForUpdate(userId)
                .orElseThrow(() -> new AccessDeniedException("Active account is required"));
        requireActiveOwner(user);
        return cartRepository
                .findByUserIdForUpdate(userId)
                .orElseThrow(() -> new EntityNotFoundException(String.format("Cart not found for user %s", userId)));
    }

    @Transactional
    public CartItem addItem(Long userId, Long productVariantId, Integer quantity) {
        validateQuantity(quantity);
        Cart cart = getMyCartForUpdate(userId);
        ProductVariant variant = productVariantRepository
                .findById(productVariantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        String.format("ProductVariant with id %s not found", productVariantId)));

        return cartItemRepository
                .findByCartIdAndProductVariantId(cart.getId(), productVariantId)
                .map(existing -> mergeQuantity(existing, quantity))
                .orElseGet(() -> save(CartItem.create(cart, variant, quantity)));
    }

    @Transactional
    public CartItem updateItemQuantity(Long userId, Long cartItemId, Integer quantity) {
        validateQuantity(quantity);
        getMyCartForUpdate(userId);
        CartItem item = findOwned(userId, cartItemId);
        item.setQuantity(quantity);
        return save(item);
    }

    @Transactional
    public boolean removeItem(Long userId, Long cartItemId) {
        getMyCartForUpdate(userId);
        return cartItemRepository
                .findById(cartItemId)
                .map(item -> {
                    requireOwnership(userId, item);
                    cartItemRepository.deleteById(cartItemId);
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public boolean clear(Long userId) {
        Cart cart = getMyCartForUpdate(userId);
        cartItemRepository.deleteAll(cartItemRepository.findAllByCartId(cart.getId()));
        return true;
    }

    /**
     * Resolves an owned {@link CartItem} by id - used by the nested
     * {@code CartItem.cart} resolver so it can't be used to read another
     * user's cart item.
     */
    @Transactional(readOnly = true)
    public CartItem getOwnedCartItem(Long userId, Long cartItemId) {
        CartItem item = findOwned(userId, cartItemId);
        requireActiveOwner(item.getCart().getUser());
        return item;
    }

    private void requireActiveOwner(User user) {
        if (user == null || user.isDeleted() || user.getDeletionIdentityId() != null) {
            throw new AccessDeniedException("Active account is required");
        }
    }

    private CartItem findOwned(Long userId, Long cartItemId) {
        CartItem item = cartItemRepository
                .findById(cartItemId)
                .orElseThrow(
                        () -> new EntityNotFoundException(String.format("CartItem with id %s not found", cartItemId)));
        requireOwnership(userId, item);
        return item;
    }

    private void validateQuantity(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
    }

    private CartItem save(CartItem item) {
        return cartItemRepository.save(item);
    }

    private CartItem mergeQuantity(CartItem existing, Integer additionalQuantity) {
        existing.setQuantity(Math.addExact(existing.getQuantity(), additionalQuantity));
        return cartItemRepository.save(existing);
    }

    private void requireOwnership(Long userId, CartItem item) {
        Long ownerId = item.getCart() == null || item.getCart().getUser() == null
                ? null
                : item.getCart().getUser().getId();
        if (!Objects.equals(userId, ownerId)) {
            throw new AccessDeniedException("Cart item does not belong to the current user");
        }
    }
}
