package ee.bytecore.backend.repositories.payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ee.bytecore.backend.entities.payment.Order;

import jakarta.persistence.LockModeType;

public interface OrderRepository extends JpaRepository<Order, Long> {
    @Override
    @EntityGraph(attributePaths = "user")
    Optional<Order> findById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(Long id);

    @EntityGraph(attributePaths = "user")
    List<Order> findAllByUserId(Long userId);

    @EntityGraph(attributePaths = "user")
    Optional<Order> findByPublicId(UUID publicId);

    String SEARCH_WHERE =
            """
            FROM orders o LEFT JOIN users u ON u.id = o.user_id
            WHERE (CAST(:userId AS bigint) IS NULL OR o.user_id = CAST(:userId AS bigint))
              AND (CAST(:status AS varchar) IS NULL OR CAST(o.status AS varchar) = CAST(:status AS varchar))
              AND (CAST(:fromAt AS timestamptz) IS NULL OR o.created_at >= CAST(:fromAt AS timestamptz))
              AND (CAST(:toAt AS timestamptz) IS NULL OR o.created_at < CAST(:toAt AS timestamptz))
              AND (CAST(:query AS varchar) IS NULL
                   OR CAST(o.public_id AS varchar) LIKE lower(CAST(:query AS varchar)) || '%'
                   OR u.email_index = CAST(:userEmailIndex AS varchar)
                   OR (u.email_index IS NULL AND lower(u.email) = lower(CAST(:query AS varchar)))
                   OR lower(u.username) = lower(CAST(:query AS varchar))
                   OR o.checkout_snapshot ->> 'contactEmailIndex' = CAST(:emailIndex AS varchar))
            """;

    /**
     * Bounded, newest-first order search. {@code userId} null means all owners (staff only);
     * other null filters are ignored. {@code toAt} is exclusive.
     */
    @Query(
            value = "SELECT o.* " + SEARCH_WHERE + " ORDER BY o.created_at DESC, o.id DESC",
            countQuery = "SELECT count(*) " + SEARCH_WHERE,
            nativeQuery = true)
    Page<Order> searchIndexed(
            @Param("userId") Long userId,
            @Param("status") String status,
            @Param("fromAt") java.time.OffsetDateTime fromAt,
            @Param("toAt") java.time.OffsetDateTime toAt,
            @Param("query") String query,
            @Param("emailIndex") String emailIndex,
            @Param("userEmailIndex") String userEmailIndex,
            Pageable pageable);

    /** Account and guest contact emails are encrypted at rest, so they are matched through keyed blind indexes. */
    default Page<Order> search(
            Long userId,
            String status,
            java.time.OffsetDateTime fromAt,
            java.time.OffsetDateTime toAt,
            String query,
            Pageable pageable) {
        String emailIndex = query == null || !query.contains("@")
                ? null
                : ee.bytecore.backend.security.crypto.FieldEncryption.contactEmailIndex(query);
        String userEmailIndex =
                emailIndex == null ? null : ee.bytecore.backend.security.crypto.FieldEncryption.userEmailIndex(query);
        return searchIndexed(userId, status, fromAt, toAt, query, emailIndex, userEmailIndex, pageable);
    }
}
