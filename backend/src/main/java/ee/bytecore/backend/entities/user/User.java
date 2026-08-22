package ee.bytecore.backend.entities.user;

import java.time.Instant;
import java.time.LocalDate;

import ee.bytecore.backend.enums.UserRole;

import jakarta.persistence.*;
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

  @Column(name = "email", nullable = false, unique = true)
  private String email;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Column(name = "date_of_birth", nullable = false)
  private LocalDate dateOfBirth;

  @CreationTimestamp
  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(nullable = false)
  private Instant updatedAt;

  public static User create(
      String username, String email, String passwordHash, LocalDate dateOfBirth) {
    User user = new User();
    user.username = username;
    user.email = email;
    user.passwordHash = passwordHash;
    user.dateOfBirth = dateOfBirth;
    return user;
  }
}
