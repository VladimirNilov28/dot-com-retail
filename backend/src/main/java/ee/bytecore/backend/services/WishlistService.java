package ee.bytecore.backend.services;

import java.util.Objects;

import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.entities.wishlist.Wishlist;
import ee.bytecore.backend.entities.wishlist.WishlistItem;
import ee.bytecore.backend.repositories.product.ProductVariantRepository;
import ee.bytecore.backend.repositories.user.UserRepository;
import ee.bytecore.backend.repositories.wishlist.WishlistItemRepository;
import ee.bytecore.backend.repositories.wishlist.WishlistRepository;

import jakarta.persistence.EntityNotFoundException;

@Service
public class WishlistService {

    private final WishlistRepository wishlistRepository;
    private final WishlistItemRepository wishlistItemRepository;
    private final ProductVariantRepository productVariantRepository;
    private final UserRepository userRepository;

    /**
     * Self-injected proxy used purely so {@link #createWishlist(Long)} can
     * run in its own new transaction (see {@link #getMyWishlist(Long)}) - a
     * plain self-invocation would bypass Spring's transactional proxy
     * entirely. Mirrors {@code CartService}'s lazy get-or-create pattern.
     */
    private final WishlistService self;

    public WishlistService(
            WishlistRepository wishlistRepository,
            WishlistItemRepository wishlistItemRepository,
            ProductVariantRepository productVariantRepository,
            UserRepository userRepository,
            @Lazy WishlistService self) {
        this.wishlistRepository = wishlistRepository;
        this.wishlistItemRepository = wishlistItemRepository;
        this.productVariantRepository = productVariantRepository;
        this.userRepository = userRepository;
        this.self = self;
    }

    /**
     * Returns the user's wishlist, lazily creating it on first access. A
     * missing wishlist is created in its own {@code REQUIRES_NEW}
     * transaction. Creation locks and validates the account, then rechecks
     * for a wishlist created by another request.
     */
    @Transactional(readOnly = true)
    public Wishlist getMyWishlist(Long userId) {
        Wishlist wishlist = wishlistRepository.findByUserId(userId).orElseGet(() -> self.createWishlist(userId));
        requireActiveOwner(wishlist.getUser());
        return wishlist;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Wishlist createWishlist(Long userId) {
        User user = userRepository
                .findActiveByIdForUpdate(userId)
                .orElseThrow(() -> new AccessDeniedException("Active account is required"));
        requireActiveOwner(user);
        return wishlistRepository
                .findByUserId(userId)
                .orElseGet(() -> wishlistRepository.saveAndFlush(Wishlist.create(user)));
    }

    /**
     * Resolves an owned {@link WishlistItem} by id - used by the nested
     * {@code WishlistItem.wishlist} resolver so it can't be used to read
     * another user's wishlist item.
     */
    @Transactional(readOnly = true)
    public WishlistItem getOwnedWishlistItem(Long userId, Long wishlistItemId) {
        WishlistItem item = wishlistItemRepository
                .findById(wishlistItemId)
                .orElseThrow(() -> new EntityNotFoundException(
                        String.format("WishlistItem with id %s not found", wishlistItemId)));
        Long ownerId = item.getWishlist() == null || item.getWishlist().getUser() == null
                ? null
                : item.getWishlist().getUser().getId();
        if (!Objects.equals(userId, ownerId)) {
            throw new AccessDeniedException("Wishlist item does not belong to the current user");
        }
        requireActiveOwner(item.getWishlist().getUser());
        return item;
    }

    @Transactional
    public WishlistItem addItem(Long userId, Long productVariantId) {
        Wishlist wishlist = getMyWishlist(userId);
        requireActiveOwner(userRepository
                .findActiveByIdForUpdate(userId)
                .orElseThrow(() -> new AccessDeniedException("Active account is required")));
        ProductVariant variant = productVariantRepository
                .findById(productVariantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        String.format("ProductVariant with id %s not found", productVariantId)));

        WishlistItem item = WishlistItem.create(wishlist, variant);
        try {
            return wishlistItemRepository.save(item);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("This product variant is already in the wishlist");
        }
    }

    @Transactional
    public boolean removeItem(Long userId, Long wishlistItemId) {
        requireActiveOwner(userRepository
                .findActiveByIdForUpdate(userId)
                .orElseThrow(() -> new AccessDeniedException("Active account is required")));
        return wishlistItemRepository
                .findById(wishlistItemId)
                .map(item -> {
                    Long ownerId =
                            item.getWishlist() == null || item.getWishlist().getUser() == null
                                    ? null
                                    : item.getWishlist().getUser().getId();
                    if (!Objects.equals(userId, ownerId)) {
                        throw new AccessDeniedException("Wishlist item does not belong to the current user");
                    }
                    requireActiveOwner(item.getWishlist().getUser());
                    wishlistItemRepository.deleteById(wishlistItemId);
                    return true;
                })
                .orElse(false);
    }

    private void requireActiveOwner(User user) {
        if (user == null || user.isDeleted() || user.getDeletionIdentityId() != null) {
            throw new AccessDeniedException("Active account is required");
        }
    }
}
