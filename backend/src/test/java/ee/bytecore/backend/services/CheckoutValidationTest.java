package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import ee.bytecore.backend.config.CheckoutSettings;
import ee.bytecore.backend.services.CheckoutValues.Address;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("unit")
class CheckoutValidationTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "invalid", "a b@example.com", "a@example", "a@b@c.ee", "a\n@example.com"})
    void invalidEmailIsRejected(String email) {
        assertThatThrownBy(() -> CheckoutSupport.email(email)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addressStructureLengthsAndControlCharactersAreValidated() {
        assertThatThrownBy(() -> CheckoutSupport.normalize(
                        new Address("a".repeat(51), "Name", "Tallinn", "EE", "10111", "Street", null, null)))
                .hasMessageContaining("First name");
        assertThatThrownBy(() ->
                        CheckoutSupport.normalize(new Address("Ada", "Name", "", "EE", "10111", "Street", null, null)))
                .hasMessageContaining("City");
        assertThatThrownBy(() -> CheckoutSupport.normalize(
                        new Address("Ada", "Name", "Tallinn", "EE", "", "Street", null, null)))
                .hasMessageContaining("Postal code");
        assertThatThrownBy(() -> CheckoutSupport.normalize(
                        new Address("Ada", "Name", "Tallinn", "EE", "10111", "Street\nAnother", null, null)))
                .hasMessageContaining("characters");
        assertThatThrownBy(() -> CheckoutSupport.email("a".repeat(250) + "@example.com"))
                .hasMessageContaining("length");
        Address normalized = CheckoutSupport.normalize(
                new Address(" Ada ", " Name ", " Tallinn ", "ee", "10111", " Street ", "", null));
        assertThat(normalized.firstName()).isEqualTo("Ada");
        assertThat(normalized.countryCode()).isEqualTo("EE");
        assertThat(normalized.addressLine2()).isNull();
    }

    @Test
    void canonicalHashesIgnoreMapKeyOrderAndAttributeCopiesAreIndependent() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("size", "M");
        first.put("color", Map.of("name", "black"));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("color", Map.of("name", "black"));
        second.put("size", "M");
        assertThat(CheckoutSupport.fingerprint(first)).isEqualTo(CheckoutSupport.fingerprint(second));
        var copy = CheckoutSupport.copyAttributes(first);
        first.put("size", "XL");
        assertThat(copy).containsEntry("size", "M");
    }

    @Test
    void shippingConfigurationRejectsInvalidChargesCountriesAndLifetime() {
        var settings = new CheckoutSettings();
        settings.validate();
        assertThat(settings.options("EE")).hasSize(3);
        assertThat(settings.options("DE"))
                .extracting(CheckoutValues.ShippingOption::method)
                .containsExactly("PICKUP");
        settings.setExpressCharge(new BigDecimal("1.001"));
        assertThatThrownBy(settings::validate).hasMessageContaining("two-decimal");
        settings.setExpressCharge(new BigDecimal("9.99"));
        settings.setSupportedCountries(java.util.List.of("XX"));
        assertThatThrownBy(settings::validate).hasMessageContaining("ISO");
        settings.setSupportedCountries(java.util.List.of("EE"));
        settings.setGuestOrderTtl(java.time.Duration.ZERO);
        assertThatThrownBy(settings::validate).hasMessageContaining("positive");
    }

    @Test
    void unsupportedMonetaryPrecisionIsRejectedWithoutRounding() {
        assertThatThrownBy(() -> CheckoutSupport.amount(new BigDecimal("100000000.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CheckoutSupport.amount(new BigDecimal("1.001")))
                .isInstanceOf(ArithmeticException.class);
        assertThat(CheckoutSupport.amount(BigDecimal.ONE)).isEqualTo(new BigDecimal("1.00"));
    }
}
