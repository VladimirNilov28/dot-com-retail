package ee.bytecore.backend.repositories.cart;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import ee.bytecore.backend.entities.cart.Cart;

import jakarta.persistence.LockModeType;

public interface CartRepository extends JpaRepository<Cart, Long> {
    Optional<Cart> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cart c where c.user.id = :userId")
    Optional<Cart> findByUserIdForUpdate(Long userId);

    Optional<Cart> findByGuestCredentialHashAndUserIsNull(String credentialHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cart c where c.guestCredentialHash = :credentialHash and c.user is null")
    Optional<Cart> findGuestForUpdate(String credentialHash);
}
