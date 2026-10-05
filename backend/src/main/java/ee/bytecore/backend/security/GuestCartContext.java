package ee.bytecore.backend.security;

import java.time.Duration;
import java.time.Instant;

import org.springframework.http.ResponseCookie;

import ee.bytecore.backend.config.GuestCartSettings;

public final class GuestCartContext {
    public static final String KEY = "guestCartContext";
    public static final String COOKIE = "retail_guest_cart";

    private String credential;
    private String responseCookie;
    private final GuestCartSettings settings;

    public GuestCartContext(String credential, GuestCartSettings settings) {
        this.credential = credential;
        this.settings = settings;
    }

    public String credential() {
        return credential;
    }

    public String responseCookie() {
        return responseCookie;
    }

    public void issue(String credential, Instant expiresAt) {
        this.credential = credential;
        responseCookie = cookie(credential, Duration.between(Instant.now(), expiresAt));
    }

    public void clear() {
        responseCookie = cookie("", Duration.ZERO);
        credential = null;
    }

    private String cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE, value)
                .httpOnly(true)
                .sameSite("Lax")
                .secure(settings.isSecure())
                .path("/graphql")
                .maxAge(maxAge)
                .build()
                .toString();
    }
}
