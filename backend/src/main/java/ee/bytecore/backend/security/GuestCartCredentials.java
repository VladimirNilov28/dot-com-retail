package ee.bytecore.backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import ee.bytecore.backend.exceptions.GuestCartUnavailableException;

public final class GuestCartCredentials {
    private static final SecureRandom RANDOM = new SecureRandom();

    private GuestCartCredentials() {}

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String credential) {
        if (credential == null || !credential.matches("[A-Za-z0-9_-]{43}")) {
            throw new GuestCartUnavailableException();
        }
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(credential.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
