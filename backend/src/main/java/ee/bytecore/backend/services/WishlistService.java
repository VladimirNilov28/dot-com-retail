package ee.bytecore.backend.services;

import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.entities.wishlist.Wishlist;
import ee.bytecore.backend.entities.wishlist.WishlistItem;
import ee.bytecore.backend.repositories.product.ProductVariantRepository;
import ee.bytecore.backend.repositories.wishlist.WishlistItemRepository;
import ee.bytecore.backend.repositories.wishlist.WishlistRepository;

import jakarta.persistence.EntityNotFoundException;

@Service
public class WishlistService {

    private final WishlistRepository wishlistRepository;
    private final WishlistItemRepository wishlistItemRepository;
    private final ProductVariantRepository productVariantRepository;

    public WishlistService(
            WishlistRepository wishlistRepository,
            WishlistItemRepository wishlistItemRepository,
            ProductVariantRepository productVariantRepository) {
        this.wishlistRepository = wishlistRepository;
        this.wishlistItemRepository = wishlistItemRepository;
        this.productVariantRepository = productVariantRepository;
    }

    public Wishlist getMyWishlist(Long userId) {
        return wishlistRepository
                .findByUserId(userId)
                .orElseThrow(
                        () -> new EntityNotFoundException(String.format("Wishlist not found for user %s", userId)));
    }

    public WishlistItem addItem(Long userId, Long productVariantId) {
        Wishlist wishlist = getMyWishlist(userId);
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

    public boolean removeItem(Long userId, Long wishlistItemId) {
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
                    wishlistItemRepository.deleteById(wishlistItemId);
                    return true;
                })
                .orElse(false);
    }
}
