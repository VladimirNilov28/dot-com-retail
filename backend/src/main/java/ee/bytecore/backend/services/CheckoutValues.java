package ee.bytecore.backend.services;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Value snapshots shared by order persistence and the checkout projection. */
public final class CheckoutValues {
    private CheckoutValues() {}

    public record Address(
            String firstName,
            String lastName,
            String city,
            String countryCode,
            String postalCode,
            String addressLine1,
            String addressLine2,
            String phone) {}

    public record PickupLocation(String name, Address address) {}

    public record ShippingOption(
            String method,
            BigDecimal charge,
            String currency,
            String estimate,
            List<String> supportedCountries,
            PickupLocation pickupLocation) {}

    public record Selection(
            String contactEmail,
            String contactPhone,
            String shippingMethod,
            Long shippingAddressId,
            Address shippingAddress,
            Long billingAddressId,
            Address billingAddress,
            boolean billingSameAsShipping,
            String paymentSelection,
            Long paymentMethodId) {}

    public record Placement(UUID requestId, String acceptedQuoteVersion, Selection checkout) {}

    public record Line(
            Long productVariantId,
            String productName,
            String sku,
            Map<String, Object> attributes,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal subtotal) {}

    public record Details(
            String contactEmail,
            String contactPhone,
            Address shippingAddress,
            Address billingAddress,
            ShippingOption shipping,
            String paymentSelection,
            String paymentMethodType) {}

    public record Totals(
            BigDecimal merchandiseSubtotal, BigDecimal shippingCharge, BigDecimal total, String currency) {}

    public record Issue(String code, Long productVariantId, String message) {}

    public record Preview(
            String quoteVersion,
            boolean placeable,
            List<Line> lines,
            Details details,
            Totals totals,
            List<Issue> issues,
            List<ShippingOption> availableShippingOptions) {}

    public record Snapshot(int version, UUID requestId, List<Line> lines, Details details, Totals totals) {}

    public record Confirmation(
            UUID publicId,
            UUID requestId,
            String status,
            String cancellationReason,
            List<Line> lines,
            Details details,
            Totals totals,
            Instant createdAt,
            String paymentInteraction) {}
}
