package ee.bytecore.backend.repositories.user;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ee.bytecore.backend.entities.user.UserRegistrationReservation;

import jakarta.persistence.LockModeType;

public interface UserRegistrationReservationRepository extends JpaRepository<UserRegistrationReservation, UUID> {
    Optional<UserRegistrationReservation> findByFlowId(UUID flowId);

    Optional<UserRegistrationReservation> findByUsername(String username);

    /** Case-insensitive lookup through the keyed email index (legacy unindexed rows match their plaintext). */
    default Optional<UserRegistrationReservation> findByEmail(String email) {
        if (email == null) return Optional.empty();
        return findByEmailIndex(
                ee.bytecore.backend.security.crypto.FieldEncryption.userEmailIndex(email),
                ee.bytecore.backend.security.crypto.FieldCipher.normalizeEmail(email));
    }

    @Query(
            value = "select * from user_registration_reservations where email_index = :emailIndex"
                    + " or (email_index is null and lower(trim(email)) = :normalized) limit 1",
            nativeQuery = true)
    Optional<UserRegistrationReservation> findByEmailIndex(
            @Param("emailIndex") String emailIndex, @Param("normalized") String normalized);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from UserRegistrationReservation r where r.id = :id")
    Optional<UserRegistrationReservation> findLockedById(@Param("id") UUID id);

    @Query(value = "select pg_advisory_xact_lock(hashtextextended(:claim, 0))::text", nativeQuery = true)
    String lockClaim(@Param("claim") String claim);

    List<UserRegistrationReservation> findTop50ByKratosIdentityIdIsNullAndExpiresAtBeforeOrderByExpiresAt(
            Instant cutoff);
}
