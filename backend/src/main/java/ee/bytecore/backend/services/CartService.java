package ee.bytecore.backend.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.config.GuestCartSettings;
import ee.bytecore.backend.entities.cart.Cart;
import ee.bytecore.backend.entities.cart.CartItem;
import ee.bytecore.backend.entities.cart.CartMergeLine;
import ee.bytecore.backend.entities.cart.GuestCartMergeReceipt;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.exceptions.GuestCartUnavailableException;
import ee.bytecore.backend.repositories.cart.CartItemRepository;
import ee.bytecore.backend.repositories.cart.CartRepository;
import ee.bytecore.backend.repositories.cart.GuestCartMergeReceiptRepository;
import ee.bytecore.backend.repositories.product.ProductVariantRepository;
import ee.bytecore.backend.repositories.user.UserRepository;
import ee.bytecore.backend.security.GuestCartCredentials;

import jakarta.persistence.EntityNotFoundException;

@Service
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductVariantRepository productVariantRepository;
    private final UserRepository userRepository;

    // Providers preserve the existing authenticated-only test slices and constructor.
    // Guest operations require these beans; getObject fails explicitly if miswired.
    @Autowired
    private ObjectProvider<GuestCartSettings> guestSettings;

    @Autowired
    private ObjectProvider<GuestCartMergeReceiptRepository> guestReceipts;

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
        requireUserId(userId);
        Cart cart = cartRepository.findByUserId(userId).orElseGet(() -> self.createCart(userId));
        requireActiveOwner(cart.getUser());
        return cart;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Cart createCart(Long userId) {
        requireUserId(userId);
        User user = userRepository
                .findActiveByIdForUpdate(userId)
                .orElseThrow(() -> new AccessDeniedException("Active account is required"));
        requireActiveOwner(user);
        return cartRepository.findByUserId(userId).orElseGet(() -> cartRepository.saveAndFlush(Cart.create(user)));
    }

    @Transactional
    public Cart getMyCartForUpdate(Long userId) {
        requireUserId(userId);
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

    public record GuestMergeConflict(
            Long productVariantId, String code, int userQuantity, int guestQuantity, String message) {}

    public record GuestMergeResult(
            String status, Cart cart, List<CartMergeLine> mergedItems, List<GuestMergeConflict> conflicts) {}

    @Transactional
    public Cart createGuestCart(String credential) {
        return snapshot(cartRepository.saveAndFlush(Cart.createGuest(
                GuestCartCredentials.hash(credential),
                Instant.now()
                        .plus(guestSettings.getObject().getTtl())
                        .truncatedTo(java.time.temporal.ChronoUnit.MICROS))));
    }

    @Transactional(readOnly = true)
    public Cart getGuestCart(String credential) {
        if (credential == null) return null;
        Cart cart = cartRepository
                .findByGuestCredentialHashAndUserIsNull(GuestCartCredentials.hash(credential))
                .orElseThrow(GuestCartUnavailableException::new);
        requireLiveGuest(cart);
        return snapshot(cart);
    }

    @Transactional
    public Cart addGuestItem(String credential, Long variantId, Integer quantity) {
        validateQuantity(quantity);
        Cart cart = lockGuest(credential);
        ProductVariant variant = activeVariant(variantId);
        cartItemRepository
                .findByCartIdAndProductVariantId(cart.getId(), variantId)
                .ifPresentOrElse(
                        existing -> mergeQuantity(existing, quantity),
                        () -> save(CartItem.create(cart, variant, quantity)));
        cart.setUpdatedAt(Instant.now());
        cartItemRepository.flush();
        return snapshot(cart);
    }

    @Transactional
    public Cart updateGuestQuantity(String credential, Long itemId, Integer quantity) {
        validateQuantity(quantity);
        Cart cart = lockGuest(credential);
        CartItem item = guestItem(cart, itemId);
        activeVariant(item.getProductVariant().getId());
        item.setQuantity(quantity);
        save(item);
        cart.setUpdatedAt(Instant.now());
        cartItemRepository.flush();
        return snapshot(cart);
    }

    @Transactional
    public Cart removeGuestItem(String credential, Long itemId) {
        Cart cart = lockGuest(credential);
        cartItemRepository.delete(guestItem(cart, itemId));
        cart.setUpdatedAt(Instant.now());
        cartItemRepository.flush();
        return snapshot(cart);
    }

    @Transactional
    public GuestMergeResult mergeGuestCart(Long userId, String credential, UUID requestId) {
        if (userId == null || requestId == null) throw new AccessDeniedException("Active account is required");
        User user = userRepository
                .findActiveByIdForUpdate(userId)
                .orElseThrow(() -> new AccessDeniedException("Active account is required"));
        requireActiveOwner(user);
        var receipts = guestReceipts.getObject();
        var receipt = receipts.findByUserIdAndRequestId(userId, requestId);
        Cart destination = cartRepository.findByUserIdForUpdate(userId).orElse(null);
        if (receipt.isPresent()) {
            GuestCartMergeReceipt previous = receipt.get();
            if (!previous.getExpiresAt().isAfter(Instant.now())) throw new GuestCartUnavailableException();
            if (credential != null
                    && !previous.getSourceCredentialHash().equals(GuestCartCredentials.hash(credential))) {
                throw new IllegalArgumentException("Merge request ID already belongs to another guest cart");
            }
            if (destination == null) throw new GuestCartUnavailableException();
            return new GuestMergeResult("REPLAYED", snapshot(destination), previous.getMergedItems(), List.of());
        }
        Cart source = lockGuest(credential);
        List<CartItem> guestItems = cartItemRepository.findAllByCartId(source.getId());
        guestItems.sort(
                java.util.Comparator.comparing(item -> item.getProductVariant().getId()));
        Map<Long, CartItem> existing = new HashMap<>();
        if (destination != null) {
            cartItemRepository
                    .findAllByCartId(destination.getId())
                    .forEach(item -> existing.put(item.getProductVariant().getId(), item));
        }
        List<GuestMergeConflict> conflicts = new ArrayList<>();
        List<CartMergeLine> lines = new ArrayList<>();
        for (CartItem item : guestItems) {
            Long id = item.getProductVariant().getId();
            int before = existing.containsKey(id) ? existing.get(id).getQuantity() : 0;
            var validated = productVariantRepository.findByIdForCartValidation(id);
            if (validated.isEmpty() || !Boolean.TRUE.equals(validated.get().getIsActive())) {
                conflicts.add(new GuestMergeConflict(
                        id,
                        "VARIANT_UNAVAILABLE",
                        before,
                        item.getQuantity(),
                        "Variant is unavailable; remove it from the guest cart before retrying"));
                continue;
            }
            try {
                lines.add(new CartMergeLine(id, before, item.getQuantity(), Math.addExact(before, item.getQuantity())));
            } catch (ArithmeticException exception) {
                conflicts.add(new GuestMergeConflict(
                        id,
                        "QUANTITY_OVERFLOW",
                        before,
                        item.getQuantity(),
                        "Combined quantity exceeds the supported integer range"));
            }
        }
        if (!conflicts.isEmpty()) {
            return new GuestMergeResult(
                    "BLOCKED", destination == null ? null : snapshot(destination), List.of(), conflicts);
        }
        if (destination == null) destination = cartRepository.saveAndFlush(Cart.create(user));
        for (CartItem guestItem : guestItems) {
            CartItem target = existing.get(guestItem.getProductVariant().getId());
            if (target == null)
                save(CartItem.create(destination, guestItem.getProductVariant(), guestItem.getQuantity()));
            else mergeQuantity(target, guestItem.getQuantity());
        }
        destination.setUpdatedAt(Instant.now());
        cartItemRepository.flush();
        Instant now = Instant.now();
        receipts.saveAndFlush(GuestCartMergeReceipt.create(
                user,
                requestId,
                source,
                lines,
                now,
                now.plus(guestSettings.getObject().getMergeReceiptTtl())));
        cartItemRepository.deleteAll(guestItems);
        cartItemRepository.flush();
        cartRepository.delete(source);
        cartRepository.flush();
        return new GuestMergeResult("MERGED", snapshot(destination), lines, List.of());
    }

    public static BigDecimal lineSubtotal(CartItem item) {
        return item.getProductVariant()
                .getPrice()
                .multiply(BigDecimal.valueOf(item.getQuantity()))
                .setScale(2, RoundingMode.UNNECESSARY);
    }

    public static BigDecimal subtotal(Cart cart) {
        return cart.getItems().stream().map(CartService::lineSubtotal).reduce(new BigDecimal("0.00"), BigDecimal::add);
    }

    private Cart snapshot(Cart cart) {
        cart.setItems(cartItemRepository.findAllByCartId(cart.getId()));
        return cart;
    }

    private Cart lockGuest(String credential) {
        Cart cart = cartRepository
                .findGuestForUpdate(GuestCartCredentials.hash(credential))
                .orElseThrow(GuestCartUnavailableException::new);
        requireLiveGuest(cart);
        return cart;
    }

    @Transactional
    public Cart getGuestCartForUpdate(String credential) {
        return snapshot(lockGuest(credential));
    }

    @Transactional
    public void consumeCheckoutCart(Cart cart) {
        cartItemRepository.deleteAll(cartItemRepository.findAllByCartId(cart.getId()));
        cartItemRepository.flush();
        if (cart.getUser() == null) {
            cartRepository.delete(cart);
            cartRepository.flush();
        }
    }

    private void requireLiveGuest(Cart cart) {
        if (cart.getUser() != null
                || cart.getGuestExpiresAt() == null
                || !cart.getGuestExpiresAt().isAfter(Instant.now())) throw new GuestCartUnavailableException();
    }

    private ProductVariant activeVariant(Long id) {
        ProductVariant variant = productVariantRepository
                .findByIdForCartValidation(id)
                .orElseThrow(() -> new IllegalArgumentException("Product variant is unavailable"));
        if (!Boolean.TRUE.equals(variant.getIsActive()))
            throw new IllegalArgumentException("Product variant is unavailable");
        return variant;
    }

    private CartItem guestItem(Cart cart, Long itemId) {
        return cartItemRepository
                .findById(itemId)
                .filter(item -> Objects.equals(cart.getId(), item.getCart().getId()))
                .orElseThrow(() -> new AccessDeniedException("Cart item is unavailable in this guest cart"));
    }

    private void requireActiveOwner(User user) {
        if (user == null || user.isDeleted() || user.getDeletionIdentityId() != null) {
            throw new AccessDeniedException("Active account is required");
        }
    }

    private void requireUserId(Long userId) {
        if (userId == null) throw new AccessDeniedException("Active account is required");
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
        try {
            existing.setQuantity(Math.addExact(existing.getQuantity(), additionalQuantity));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Combined quantity exceeds the supported integer range");
        }
        return cartItemRepository.save(existing);
    }

    private void requireOwnership(Long userId, CartItem item) {
        Long ownerId = item.getCart() == null || item.getCart().getUser() == null
                ? null
                : item.getCart().getUser().getId();
        if (userId == null || ownerId == null || !Objects.equals(userId, ownerId)) {
            throw new AccessDeniedException("Cart item does not belong to the current user");
        }
    }
}
