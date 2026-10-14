package ee.bytecore.backend.entities.user;

import java.time.Instant;

import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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

    @Convert(converter = ee.bytecore.backend.security.crypto.EncryptedStringConverter.class)
    @Size(max = 50) @Column(name = "first_name", length = 1024)
    private String firstName;

    @Convert(converter = ee.bytecore.backend.security.crypto.EncryptedStringConverter.class)
    @Size(max = 50) @Column(name = "last_name", length = 1024)
    private String lastName;

    @Convert(converter = ee.bytecore.backend.security.crypto.EncryptedStringConverter.class)
    @Size(max = 100) @Column(name = "city", length = 1024)
    private String city;

    @Convert(converter = ee.bytecore.backend.security.crypto.EncryptedStringConverter.class)
    @Size(max = 200) @Column(name = "country", length = 1024)
    private String country;

    @Convert(converter = ee.bytecore.backend.security.crypto.EncryptedStringConverter.class)
    @Size(max = 10) @Column(name = "postal_code", length = 1024)
    private String postalCode;

    @Convert(converter = ee.bytecore.backend.security.crypto.EncryptedStringConverter.class)
    @Size(max = 255) @Column(name = "address_line1", length = 1024)
    private String addressLine1;

    @Convert(converter = ee.bytecore.backend.security.crypto.EncryptedStringConverter.class)
    @Size(max = 255) @Column(name = "address_line2", length = 1024)
    private String addressLine2;

    @Convert(converter = ee.bytecore.backend.security.crypto.EncryptedStringConverter.class)
    @Size(max = 20) @Column(name = "mobile", length = 1024)
    private String mobile;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public static UserAddress create(
            User user,
            String firstName,
            String lastName,
            String city,
            String country,
            String postalCode,
            String addressLine1,
            String addressLine2,
            String mobile) {
        UserAddress userAddress = new UserAddress();
        userAddress.user = user;
        userAddress.firstName = firstName;
        userAddress.lastName = lastName;
        userAddress.city = city;
        userAddress.country = country;
        userAddress.postalCode = postalCode;
        userAddress.addressLine1 = addressLine1;
        userAddress.addressLine2 = addressLine2;
        userAddress.mobile = mobile;
        return userAddress;
    }
}
