package ee.bytecore.backend.security.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Exposes the configured {@link FieldCipher} to JPA converters, which Hibernate may instantiate outside
 * Spring. The key ring comes from {@code APP_DATA_KEYS} (never from Git).
 */
@Component
public class FieldEncryption {
    private static volatile FieldCipher current;

    public FieldEncryption(
            @Value("${app.data-encryption.keys:}") String keys,
            @Value("${app.data-encryption.active-key:}") String activeKey) {
        current = FieldCipher.fromKeyRing(keys, activeKey);
    }

    public FieldCipher cipher() {
        return current;
    }

    /** Encrypts a value for a column written through JDBC rather than a JPA converter. */
    public static String seal(String plaintext) {
        return required().encrypt(plaintext);
    }

    /** Decrypts a JDBC-read column; legacy plaintext passes through. */
    public static String open(String stored) {
        return required().decrypt(stored);
    }

    public static String contactEmailIndex(String email) {
        return SealedSnapshotConverter.emailIndex(required(), email);
    }

    /** Keyed, versioned blind index used for account email lookups and uniqueness. */
    public static String userEmailIndex(String email) {
        return required().userEmailIndex(email);
    }

    static FieldCipher required() {
        FieldCipher cipher = current;
        if (cipher == null) throw new IllegalStateException("Field encryption is not initialised");
        return cipher;
    }

    /** Test hook for converter unit tests that run without a Spring context. */
    static void install(FieldCipher cipher) {
        current = cipher;
    }
}
