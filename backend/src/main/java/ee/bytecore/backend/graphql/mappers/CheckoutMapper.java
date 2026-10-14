package ee.bytecore.backend.graphql.mappers;

import ee.bytecore.backend.services.CheckoutValues;

import com.netflix.dgs.codegen.generated.types.CheckoutAddressInput;
import com.netflix.dgs.codegen.generated.types.CheckoutInput;
import com.netflix.dgs.codegen.generated.types.PlaceOrderInput;

public final class CheckoutMapper {
    private CheckoutMapper() {}

    public static CheckoutValues.Placement placement(PlaceOrderInput input) {
        return new CheckoutValues.Placement(
                input.getRequestId(), input.getAcceptedQuoteVersion(), selection(input.getCheckout()));
    }

    public static CheckoutValues.Selection selection(CheckoutInput input) {
        return new CheckoutValues.Selection(
                input.getContactEmail(),
                input.getContactPhone(),
                input.getShippingMethod().name(),
                id(input.getShippingAddressId()),
                address(input.getShippingAddress()),
                id(input.getBillingAddressId()),
                address(input.getBillingAddress()),
                Boolean.TRUE.equals(input.getBillingSameAsShipping()),
                input.getPaymentSelection().name(),
                id(input.getPaymentMethodId()));
    }

    private static CheckoutValues.Address address(CheckoutAddressInput input) {
        if (input == null) return null;
        return new CheckoutValues.Address(
                input.getFirstName(),
                input.getLastName(),
                input.getCity(),
                input.getCountryCode(),
                input.getPostalCode(),
                input.getAddressLine1(),
                input.getAddressLine2(),
                input.getPhone());
    }

    private static Long id(String id) {
        if (id == null) return null;
        try {
            long value = Long.parseLong(id);
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Saved selection ID must be a positive integer");
        }
    }
}
