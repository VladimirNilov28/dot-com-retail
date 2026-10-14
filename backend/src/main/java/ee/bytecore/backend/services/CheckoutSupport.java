package ee.bytecore.backend.services;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

import ee.bytecore.backend.exceptions.CheckoutException;
import ee.bytecore.backend.services.CheckoutValues.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public final class CheckoutSupport {
    private static final ObjectMapper JSON =
            new ObjectMapper().findAndRegisterModules().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");

    private CheckoutSupport() {}

    public static Selection normalize(Selection input) {
        if (input == null) throw new IllegalArgumentException("Checkout selections are required");
        return new Selection(
                text(input.contactEmail(), 255, false, "Contact email"),
                text(input.contactPhone(), 20, false, "Contact phone"),
                input.shippingMethod(),
                input.shippingAddressId(),
                normalize(input.shippingAddress()),
                input.billingAddressId(),
                normalize(input.billingAddress()),
                input.billingSameAsShipping(),
                input.paymentSelection(),
                input.paymentMethodId());
    }

    public static Address normalize(Address address) {
        if (address == null) return null;
        return new Address(
                text(address.firstName(), 50, true, "First name"),
                text(address.lastName(), 50, true, "Last name"),
                text(address.city(), 100, true, "City"),
                text(address.countryCode(), 2, true, "Country code").toUpperCase(Locale.ROOT),
                text(address.postalCode(), 10, true, "Postal code"),
                text(address.addressLine1(), 255, true, "Address line 1"),
                text(address.addressLine2(), 255, false, "Address line 2"),
                text(address.phone(), 20, false, "Address phone"));
    }

    public static String text(String value, int max, boolean required, String field) {
        if (value == null || value.isBlank()) {
            if (required) throw new IllegalArgumentException(field + " is required");
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > max || normalized.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(field + " has invalid length or characters");
        return normalized;
    }

    public static String email(String value) {
        String email = text(value, 255, true, "Contact email");
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            throw new IllegalArgumentException("Contact email is invalid");
        return email;
    }

    public static BigDecimal amount(BigDecimal amount) {
        if (amount == null || amount.signum() < 0 || amount.compareTo(MAX_AMOUNT) > 0)
            throw new IllegalArgumentException("Checkout amount is outside supported monetary precision");
        return amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    public static String fingerprint(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value)));
        } catch (NoSuchAlgorithmException | JsonProcessingException exception) {
            throw new IllegalStateException("Cannot calculate checkout fingerprint", exception);
        }
    }

    public static Map<String, Object> copyAttributes(Map<String, Object> attributes) {
        return JSON.convertValue(attributes, new TypeReference<Map<String, Object>>() {});
    }

    public static CheckoutException unavailable() {
        return new CheckoutException("GUEST_ORDER_UNAVAILABLE", "Guest order is unavailable or its credential expired");
    }
}
