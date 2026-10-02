package ee.bytecore.backend.repositories.wishlist;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.wishlist.WishlistItem;

public interface WishlistItemRepository extends JpaRepository<WishlistItem, Long> {}
