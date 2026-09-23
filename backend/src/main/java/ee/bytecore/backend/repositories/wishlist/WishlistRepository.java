package ee.bytecore.backend.repositories.wishlist;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.bytecore.backend.entities.wishlist.Wishlist;

public interface WishlistRepository extends JpaRepository<Wishlist, Long> {
    Optional<Wishlist> findByUserId(Long userId);
}
