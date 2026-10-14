package ee.bytecore.backend.security.crypto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import ee.bytecore.backend.services.CheckoutValues;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Stores the order checkout snapshot (contact email/phone, shipping and billing addresses) as an
 * AES-GCM sealed envelope inside the existing jsonb column:
 * {@code {"sealed": "enc:<key>:...", "contactEmailIndex": "<hmac>"}}. Legacy plaintext snapshots stay
 * readable until {@link EncryptionBackfill} seals them.
 */
@Converter
public class SealedSnapshotConverter implements AttributeConverter<CheckoutValues.Snapshot, Map<String, Object>> {
    public static final String SEALED = "sealed";
    public static final String CONTACT_EMAIL_INDEX = "contactEmailIndex";
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Override
    public Map<String, Object> convertToDatabaseColumn(CheckoutValues.Snapshot snapshot) {
        return snapshot == null ? null : seal(snapshot, FieldEncryption.required());
    }

    @Override
    public CheckoutValues.Snapshot convertToEntityAttribute(Map<String, Object> stored) {
        if (stored == null) return null;
        try {
            Object sealed = stored.get(SEALED);
            if (sealed instanceof String value) {
                return JSON.readValue(FieldEncryption.required().decrypt(value), CheckoutValues.Snapshot.class);
            }
            return JSON.convertValue(stored, CheckoutValues.Snapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable checkout snapshot", e);
        }
    }

    public static Map<String, Object> seal(CheckoutValues.Snapshot snapshot, FieldCipher cipher) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put(SEALED, cipher.encrypt(JSON.writeValueAsString(snapshot)));
            String email = snapshot.details() == null ? null : snapshot.details().contactEmail();
            if (email != null) envelope.put(CONTACT_EMAIL_INDEX, emailIndex(cipher, email));
            return envelope;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Checkout snapshot serialisation failed", e);
        }
    }

    public static String emailIndex(FieldCipher cipher, String email) {
        return cipher.blindIndex(email.trim().toLowerCase(Locale.ROOT));
    }
}
