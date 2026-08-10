package ee.bytecore.backend.entities.user;

import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "user_address")
public class UserAddress {
  protected UserAddress() {}

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Size(max = 50)
  @Column(name = "first_name", length = 50)
  private String firstName;

  @Size(max = 50)
  @Column(name = "last_name", length = 50)
  private String lastName;

  @Size(max = 100)
  @Column(name = "city", length = 100)
  private String city;

  @Size(max = 200)
  @Column(name = "country", length = 200)
  private String country;

  @Size(max = 10)
  @Column(name = "postal_code", length = 10)
  private String postalCode;

  @Size(max = 255)
  @Column(name = "address_line1")
  private String addressLine1;

  @Size(max = 255)
  @Column(name = "address_line2")
  private String addressLine2;

  @Size(max = 20)
  @Column(name = "mobile", length = 20)
  private String mobile;

  @CreationTimestamp
  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(nullable = false)
  private Instant updatedAt;
}
