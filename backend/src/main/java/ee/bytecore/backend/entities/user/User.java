package ee.bytecore.backend.entities.user;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import ee.bytecore.backend.enums.UserRole;
import ee.bytecore.backend.security.crypto.EncryptedLocalDateConverter;
import ee.bytecore.backend.security.crypto.EncryptedStringConverter;
import ee.bytecore.backend.security.crypto.FieldEncryption;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "users")
public class User {
    protected User() {}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "role", nullable = false)
    private UserRole role = UserRole.USER;

    @Column(name = "username", nullable = false, unique = true)
    private String username;

    /** AES-GCM ciphertext at rest; looked up and kept unique through {@link #emailIndex}. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "email", nullable = false)
    private String email;

    /** Keyed blind index of the normalized email; maintained on every write. */
    @Setter(AccessLevel.NONE)
    @Column(name = "email_index", unique = true)
    private String emailIndex;

    @Convert(converter = EncryptedLocalDateConverter.class)
    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Column(name = "deletion_identity_id")
    private UUID deletionIdentityId;

    @Column(name = "kratos_identity_id", unique = true)
    private UUID kratosIdentityId;

    @Column(nullable = false)
    private boolean deleted;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void indexEmail() {
        emailIndex = FieldEncryption.userEmailIndex(email);
    }

    public static User create(String username, String email, LocalDate dateOfBirth) {
        User user = new User();
        user.username = username;
        user.email = email;
        user.dateOfBirth = dateOfBirth;
        return user;
    }
}
