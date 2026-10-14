package ee.bytecore.backend.security.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM field encryption with a versioned key ring.
 *
 * <p>Ciphertext format: {@code enc:<keyId>:<base64(iv || ciphertext || tag)>}. The key id is bound as
 * associated data. Values without the {@code enc:} prefix are legacy plaintext and are returned
 * unchanged so existing rows stay readable until the backfill re-encrypts them.
 */
public final class FieldCipher {
    public static final String PREFIX = "enc:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, SecretKey> keys;
    private final String activeKeyId;

    private FieldCipher(Map<String, SecretKey> keys, String activeKeyId) {
        this.keys = keys;
        this.activeKeyId = activeKeyId;
    }

    /**
     * Parses {@code "<id>:<base64 32-byte key>[,<id>:<key>...]"}. The first entry encrypts new data unless
     * {@code activeKeyId} names another; all entries can decrypt.
     */
    public static FieldCipher fromKeyRing(String keyRing, String activeKeyId) {
        if (keyRing == null || keyRing.isBlank()) {
            throw new IllegalStateException("APP_DATA_KEYS is required for encryption at rest");
        }
        Map<String, SecretKey> parsed = new LinkedHashMap<>();
        for (String entry : keyRing.split(",")) {
            String trimmed = entry.trim();
            int separator = trimmed.indexOf(':');
            if (separator < 1) throw new IllegalStateException("Malformed APP_DATA_KEYS entry");
            String id = trimmed.substring(0, separator);
            if (!id.matches("[A-Za-z0-9_-]{1,16}")) throw new IllegalStateException("Invalid data key id");
            byte[] material;
            try {
                material = Base64.getDecoder().decode(trimmed.substring(separator + 1));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Data key " + id + " is not valid base64");
            }
            if (material.length != 32) throw new IllegalStateException("Data key " + id + " must be 32 bytes");
            if (parsed.put(id, new SecretKeySpec(material, "AES")) != null) {
                throw new IllegalStateException("Duplicate data key id " + id);
            }
        }
        String active = activeKeyId == null || activeKeyId.isBlank() ? parsed.keySet().iterator().next() : activeKeyId;
        if (!parsed.containsKey(active)) throw new IllegalStateException("Active data key is not in the key ring");
        return new FieldCipher(Map.copyOf(parsed), active);
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(activeKeyId.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
            return PREFIX + activeKeyId + ":" + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Field encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || !isEncrypted(stored)) return stored;
        int separator = stored.indexOf(':', PREFIX.length());
        if (separator < 0) throw new IllegalStateException("Malformed encrypted value");
        String keyId = stored.substring(PREFIX.length(), separator);
        SecretKey key = keys.get(keyId);
        if (key == null) throw new IllegalStateException("Unknown data key " + keyId);
        byte[] payload = Base64.getDecoder().decode(stored.substring(separator + 1));
        if (payload.length < IV_BYTES + TAG_BITS / 8) throw new IllegalStateException("Malformed encrypted value");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES));
            cipher.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Field decryption failed", e);
        }
    }

    /** True when the value is plaintext or was sealed with a non-active key and should be rewritten. */
    public boolean needsReencryption(String stored) {
        if (stored == null) return false;
        if (!isEncrypted(stored)) return true;
        return !stored.startsWith(PREFIX + activeKeyId + ":");
    }
}
