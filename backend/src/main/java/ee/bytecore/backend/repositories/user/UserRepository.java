package ee.bytecore.backend.repositories.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    Optional<User> findByKratosIdentityId(UUID kratosIdentityId);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findLockedById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id and u.deleted = false and u.deletionIdentityId is null")
    Optional<User> findActiveByIdForUpdate(@Param("id") Long id);

    /** Locks every active ADMIN row in id order (consistent ordering avoids lock-order deadlocks). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.role = ee.bytecore.backend.enums.UserRole.ADMIN"
            + " and u.deleted = false and u.deletionIdentityId is null order by u.id")
    List<User> findActiveAdminsForUpdate();

    /** Searches active users; a null role means any role. */
    default Page<User> searchActive(String pattern, ee.bytecore.backend.enums.UserRole role, Pageable pageable) {
        // An untyped "(:role is null or ...)" parameter cannot be typed by PostgreSQL, so filter by a role set.
        return searchActiveInRoles(
                pattern,
                role == null
                        ? java.util.EnumSet.allOf(ee.bytecore.backend.enums.UserRole.class)
                        : java.util.EnumSet.of(role),
                pageable);
    }

    @Query(
            value = "select u from User u where u.deleted = false and u.deletionIdentityId is null"
                    + " and u.role in :roles"
                    + " and (lower(u.username) like :pattern escape '\\' or lower(u.email) like :pattern escape '\\')",
            countQuery = "select count(u) from User u where u.deleted = false and u.deletionIdentityId is null"
                    + " and u.role in :roles"
                    + " and (lower(u.username) like :pattern escape '\\' or lower(u.email) like :pattern escape '\\')")
    Page<User> searchActiveInRoles(
            @Param("pattern") String pattern,
            @Param("roles") java.util.Collection<ee.bytecore.backend.enums.UserRole> roles,
            Pageable pageable);

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
    @Query(value = "DELETE FROM guest_cart_merge_receipts WHERE user_id = :id", nativeQuery = true)
    void deleteGuestCartMergeReceipts(@Param("id") Long id);

    @Modifying
    @Query(value = "DELETE FROM wishlists WHERE user_id = :id", nativeQuery = true)
    void deleteWishlist(@Param("id") Long id);

    @Modifying
    @Query(value = "DELETE FROM product_ratings WHERE user_id = :id", nativeQuery = true)
    void deleteRatings(@Param("id") Long id);

    @Modifying
    @Query(value = "DELETE FROM review_votes WHERE user_id = :id", nativeQuery = true)
    void deleteReviewVotes(@Param("id") Long id);
}
