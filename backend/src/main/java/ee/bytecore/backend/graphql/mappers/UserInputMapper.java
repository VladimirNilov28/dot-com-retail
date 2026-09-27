package ee.bytecore.backend.graphql.mappers;

import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.entities.user.UserAddress;
import ee.bytecore.backend.entities.user.UserPaymentMethod;
import ee.bytecore.backend.enums.PaymentMethodType;

import com.netflix.dgs.codegen.generated.types.CreateAddressInput;
import com.netflix.dgs.codegen.generated.types.CreatePaymentMethodInput;
import com.netflix.dgs.codegen.generated.types.UpdateAddressInput;

public class UserInputMapper {
    // Mapping from graphQlType into entity
    public static UserPaymentMethod fromCreateInput(CreatePaymentMethodInput input, User user) {
        if (input == null) {
            return null;
        }
        return UserPaymentMethod.create(user, input.getProvider(), mapEnum(PaymentMethodType.class, input.getType()));
    }

    public static UserAddress fromCreateInput(CreateAddressInput input, User user) {
        if (input == null) {
            return null;
        }
        return UserAddress.create(
                user,
                input.getFirstName(),
                input.getLastName(),
                input.getCity(),
                input.getCountry(),
                input.getPostalCode(),
                input.getAddressLine1(),
                input.getAddressLine2(),
                input.getMobile());
    }

    public static void applyUpdate(UpdateAddressInput input, UserAddress existing) {
        if (input.getFirstName() != null) existing.setFirstName(input.getFirstName());
        if (input.getLastName() != null) existing.setLastName(input.getLastName());
        if (input.getCity() != null) existing.setCity(input.getCity());
        if (input.getCountry() != null) existing.setCountry(input.getCountry());
        if (input.getPostalCode() != null) existing.setPostalCode(input.getPostalCode());
        if (input.getAddressLine1() != null) existing.setAddressLine1(input.getAddressLine1());
        if (input.getAddressLine2() != null) existing.setAddressLine2(input.getAddressLine2());
        if (input.getMobile() != null) existing.setMobile(input.getMobile());
    }

    // generic for mapping from graphQlType into enum
    public static <E extends Enum<E>> E mapEnum(Class<E> targetEnumClass, Enum<?> source) {
        if (source == null) {
            return null;
        }

        return Enum.valueOf(targetEnumClass, source.name());
    }
}
