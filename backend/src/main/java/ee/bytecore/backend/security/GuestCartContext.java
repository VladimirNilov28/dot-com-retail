package ee.bytecore.backend.security;

import java.time.Duration;
import java.time.Instant;

import org.springframework.http.ResponseCookie;

import ee.bytecore.backend.config.GuestCartSettings;

public final class GuestCartContext {
    public static final String KEY = "guestCartContext";
    public static final String COOKIE = "retail_guest_cart";
    public static final String ORDER_COOKIE = "retail_guest_orders";

    private String credential;
    private String responseCookie;
    private String orderCredential;
    private String orderResponseCookie;
    private final GuestCartSettings settings;

    public GuestCartContext(String credential, GuestCartSettings settings) {
        this.credential = credential;
        this.settings = settings;
    }

    public GuestCartContext(String credential, String orderCredential, GuestCartSettings settings) {
        this(credential, settings);
        this.orderCredential = orderCredential;
    }

    public String orderCredential() {
        return orderCredential;
    }

    public java.util.List<String> responseCookies() {
        var cookies = new java.util.ArrayList<String>();
        if (responseCookie != null) cookies.add(responseCookie);
        if (orderResponseCookie != null) cookies.add(orderResponseCookie);
        return java.util.List.copyOf(cookies);
    }

    public void prepareOrderCredential(Duration lifetime) {
        if (orderCredential == null) orderCredential = GuestCartCredentials.generate();
        if (!orderCredential.matches("[A-Za-z0-9_-]{43}"))
            throw ee.bytecore.backend.services.CheckoutSupport.unavailable();
        orderResponseCookie = ResponseCookie.from(ORDER_COOKIE, orderCredential)
                .httpOnly(true)
                .sameSite("Lax")
                .secure(settings.isSecure())
                .path("/graphql")
                .maxAge(lifetime)
                .build()
                .toString();
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
