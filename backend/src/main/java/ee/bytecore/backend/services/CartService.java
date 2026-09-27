package ee.bytecore.backend.services;

import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.entities.cart.Cart;
import ee.bytecore.backend.entities.cart.CartItem;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.repositories.cart.CartItemRepository;
import ee.bytecore.backend.repositories.cart.CartRepository;
import ee.bytecore.backend.repositories.product.ProductVariantRepository;

import jakarta.persistence.EntityNotFoundException;

@Service
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductVariantRepository productVariantRepository;

    public CartService(
            CartRepository cartRepository,
            CartItemRepository cartItemRepository,
            ProductVariantRepository productVariantRepository) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.productVariantRepository = productVariantRepository;
    }

    public Cart getMyCart(Long userId) {
        return cartRepository
                .findByUserId(userId)
                .orElseThrow(() -> new EntityNotFoundException(String.format("Cart not found for user %s", userId)));
    }

    public CartItem addItem(Long userId, Long productVariantId, Integer quantity) {
        validateQuantity(quantity);
        Cart cart = getMyCart(userId);
        ProductVariant variant = productVariantRepository
                .findById(productVariantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        String.format("ProductVariant with id %s not found", productVariantId)));

        CartItem item = CartItem.create(cart, variant, quantity);
        return save(item);
    }

    @Transactional
    public CartItem updateItemQuantity(Long userId, Long cartItemId, Integer quantity) {
        validateQuantity(quantity);
        CartItem item = findOwned(userId, cartItemId);
        item.setQuantity(quantity);
        return save(item);
    }

    public boolean removeItem(Long userId, Long cartItemId) {
        return cartItemRepository
                .findById(cartItemId)
                .map(item -> {
                    requireOwnership(userId, item);
                    cartItemRepository.deleteById(cartItemId);
                    return true;
                })
                .orElse(false);
    }

    public boolean clear(Long userId) {
        Cart cart = getMyCart(userId);
        cartItemRepository.deleteAll(cart.getItems());
        return true;
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
        try {
            return cartItemRepository.save(item);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("This product variant is already in the cart");
        }
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
