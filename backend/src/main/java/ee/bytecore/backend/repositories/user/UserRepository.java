package ee.bytecore.backend.repositories.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ee.bytecore.backend.entities.user.User;

import jakarta.persistence.LockModeType;

public interface UserRepository extends JpaRepository<User, Long> {
    @Override
    @Query("select u from User u where u.id = :id and u.deleted = false and u.deletionIdentityId is null")
    Optional<User> findById(@Param("id") Long id);

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findLockedById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id and u.deleted = false and u.deletionIdentityId is null")
    Optional<User> findActiveByIdForUpdate(@Param("id") Long id);

    @Modifying
    @Query(value = "DELETE FROM user_address WHERE user_id = :id", nativeQuery = true)
    void deleteAddresses(@Param("id") Long id);

    @Modifying
    @Query(value = "DELETE FROM user_payment_methods WHERE user_id = :id", nativeQuery = true)
    void deletePaymentMethods(@Param("id") Long id);

    @Modifying
    @Query(value = "DELETE FROM carts WHERE user_id = :id", nativeQuery = true)
    void deleteCart(@Param("id") Long id);

    @Modifying
    @Query(value = "DELETE FROM wishlists WHERE user_id = :id", nativeQuery = true)
    void deleteWishlist(@Param("id") Long id);
}
