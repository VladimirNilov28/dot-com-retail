package ee.bytecore.backend.entities.user;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import ee.bytecore.backend.security.crypto.EncryptedLocalDateConverter;
import ee.bytecore.backend.security.crypto.EncryptedStringConverter;
import ee.bytecore.backend.security.crypto.FieldEncryption;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_registration_reservations")
public class UserRegistrationReservation {
    protected UserRegistrationReservation() {}

    @Id
    private UUID id;

    @Column(name = "flow_id", nullable = false, unique = true)
    private UUID flowId;

    @Column(nullable = false, unique = true)
    private String username;

    /** AES-GCM ciphertext at rest; looked up and kept unique through {@link #emailIndex}. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false)
    private String email;

    @Setter(AccessLevel.NONE)
    @Column(name = "email_index", unique = true)
    private String emailIndex;

    @Convert(converter = EncryptedLocalDateConverter.class)
    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "kratos_identity_id", unique = true)
    private UUID kratosIdentityId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    @PreUpdate
    void indexEmail() {
        emailIndex = FieldEncryption.userEmailIndex(email);
    }

    public static UserRegistrationReservation create(
            UUID flowId, String username, String email, LocalDate dateOfBirth, Instant expiresAt) {
        var reservation = new UserRegistrationReservation();
        reservation.id = UUID.randomUUID();
        reservation.flowId = flowId;
        reservation.username = username;
        reservation.email = email;
        reservation.dateOfBirth = dateOfBirth;
        reservation.expiresAt = expiresAt;
        reservation.createdAt = Instant.now();
        return reservation;
    }
}
