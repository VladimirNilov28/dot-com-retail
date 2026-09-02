package ee.bytecore.backend.graphql.mappers;

import com.netflix.dgs.codegen.generated.types.CreatePaymentMethodInput;
import com.netflix.dgs.codegen.generated.types.CreateUserInput;
import com.netflix.dgs.codegen.generated.types.UpdateRoleInput;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.entities.user.UserPaymentMethod;
import ee.bytecore.backend.enums.PaymentMethodType;
import ee.bytecore.backend.enums.UserRole;

import java.util.Arrays;

public class UserInputMapper {
    // Mapping from graphQlType into entity
    public static User fromCreateInput(CreateUserInput input, String passwordHash) {
        if (input == null) {
            return null;
        }
        return User.create(
                input.getUsername(),
                input.getEmail(),
                passwordHash,
                input.getDateOfBirth()
        ); //TODO move user creating to user service

    }

    public static UserPaymentMethod fromCreateInput(CreatePaymentMethodInput input, User user) {
        if (input == null) {
            return null;
        }
        return UserPaymentMethod.create(
                user,
                input.getProvider(),
                mapEnum(PaymentMethodType.class, input.getType())
        );
    }

    // generic for mapping from graphQlType into enum
    public static <E extends Enum<E>> E mapEnum(Class<E> targetEnumClass, Enum<?> source) {
        if (source == null) {
            return null;
        }

        return Enum.valueOf(targetEnumClass, source.name());
    }
}
