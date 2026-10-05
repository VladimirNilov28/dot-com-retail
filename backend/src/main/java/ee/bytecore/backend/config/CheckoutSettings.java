package ee.bytecore.backend.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import ee.bytecore.backend.services.CheckoutValues.Address;
import ee.bytecore.backend.services.CheckoutValues.PickupLocation;
import ee.bytecore.backend.services.CheckoutValues.ShippingOption;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;

@Component
@ConfigurationProperties("checkout")
@Getter
@Setter
public class CheckoutSettings {
    private BigDecimal standardCharge = new BigDecimal("4.99");
    private BigDecimal expressCharge = new BigDecimal("9.99");
    private BigDecimal pickupCharge = new BigDecimal("0.00");
    private String standardEstimate = "3-5 business days (development estimate)";
    private String expressEstimate = "1-2 business days (development estimate)";
    private String pickupEstimate = "1 business day (simulated readiness)";
    private List<String> supportedCountries = List.of("EE");
    private String pickupName = "Development pickup fixture - not a staffed location";
    private Address pickupAddress =
            new Address("Development", "Pickup", "Tallinn", "EE", "10111", "Example street 1 (fixture)", null, null);
    private Duration guestOrderTtl = Duration.ofDays(30);

    @PostConstruct
    public void validate() {
        for (BigDecimal charge : List.of(standardCharge, expressCharge, pickupCharge)) {
            if (charge.signum() < 0 || charge.scale() > 2 || charge.compareTo(new BigDecimal("99999999.99")) > 0)
                throw new IllegalArgumentException("Checkout shipping charges must be nonnegative two-decimal amounts");
        }
        if (supportedCountries.isEmpty() || !Set.of(Locale.getISOCountries()).containsAll(supportedCountries))
            throw new IllegalArgumentException("Checkout destinations must be ISO country codes");
        if (guestOrderTtl.isNegative() || guestOrderTtl.isZero())
            throw new IllegalArgumentException("Guest order lifetime must be positive");
        for (String value : List.of(standardEstimate, expressEstimate, pickupEstimate, pickupName)) {
            if (value.isBlank() || value.length() > 255)
                throw new IllegalArgumentException(
                        "Checkout shipping descriptions must be nonblank and <=255 characters");
        }
        if (pickupAddress == null || !supportedCountries.contains(pickupAddress.countryCode()))
            throw new IllegalArgumentException("Pickup location must use a supported country");
        pickupAddress = ee.bytecore.backend.services.CheckoutSupport.normalize(pickupAddress);
    }

    public List<ShippingOption> options(String countryCode) {
        if (countryCode != null && !supportedCountries.contains(countryCode)) {
            return List.of(
                    option("PICKUP", pickupCharge, pickupEstimate, new PickupLocation(pickupName, pickupAddress)));
        }
        return List.of(
                option("STANDARD", standardCharge, standardEstimate, null),
                option("EXPRESS", expressCharge, expressEstimate, null),
                option("PICKUP", pickupCharge, pickupEstimate, new PickupLocation(pickupName, pickupAddress)));
    }

    private ShippingOption option(String method, BigDecimal charge, String estimate, PickupLocation location) {
        return new ShippingOption(
                method, charge.setScale(2), "EUR", estimate, List.copyOf(supportedCountries), location);
    }
}
