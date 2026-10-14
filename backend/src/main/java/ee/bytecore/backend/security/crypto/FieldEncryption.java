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

    public FieldEncryption(@Value("${app.data-encryption.keys:}") String keys,
                           @Value("${app.data-encryption.active-key:}") String activeKey) {
        current = FieldCipher.fromKeyRing(keys, activeKey);
    }

    public FieldCipher cipher() {
        return current;
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
