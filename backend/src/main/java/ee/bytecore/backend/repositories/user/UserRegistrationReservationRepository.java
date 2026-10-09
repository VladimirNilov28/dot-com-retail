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

    Optional<UserRegistrationReservation> findByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from UserRegistrationReservation r where r.id = :id")
    Optional<UserRegistrationReservation> findLockedById(@Param("id") UUID id);

    @Query(value = "select pg_advisory_xact_lock(hashtextextended(:claim, 0))::text", nativeQuery = true)
    String lockClaim(@Param("claim") String claim);

    List<UserRegistrationReservation> findTop50ByKratosIdentityIdIsNullAndExpiresAtBeforeOrderByExpiresAt(
            Instant cutoff);
}
