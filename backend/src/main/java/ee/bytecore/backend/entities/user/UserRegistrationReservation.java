package ee.bytecore.backend.entities.user;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "kratos_identity_id", unique = true)
    private UUID kratosIdentityId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

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
