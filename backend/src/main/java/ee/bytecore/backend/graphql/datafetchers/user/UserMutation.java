package ee.bytecore.backend.graphql.datafetchers.user;

import com.netflix.dgs.codegen.generated.types.*;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import ee.bytecore.backend.enums.UserRole;
import ee.bytecore.backend.graphql.mappers.UserInputMapper;
import ee.bytecore.backend.graphql.mappers.UserMapper;
import ee.bytecore.backend.repositories.user.UserAddressRepository;
import ee.bytecore.backend.repositories.user.UserPaymentMethodRepository;
import ee.bytecore.backend.repositories.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.transaction.annotation.Transactional;


@DgsComponent
public class UserMutation {
    private final UserRepository userRepository;
    private final UserAddressRepository userAddressRepository;
    private final UserPaymentMethodRepository userPaymentMethodRepository;

    public UserMutation(UserRepository userRepository, UserAddressRepository userAddressRepository, UserPaymentMethodRepository userPaymentMethodRepository) {
        this.userRepository = userRepository;
        this.userAddressRepository = userAddressRepository;
        this.userPaymentMethodRepository = userPaymentMethodRepository;
    }

    // User

    @DgsMutation
    public User createUser(@InputArgument CreateUserInput input) {
        //TODO move to UserService + add duplicate email/username check + custom exceptions
        //TODO hash password before storing, do not create real users until this is done
        String plainPassword = input.getPassword();

        ee.bytecore.backend.entities.user.User newUser = UserInputMapper.fromCreateInput(input, plainPassword);
        ee.bytecore.backend.entities.user.User saved = userRepository.save(newUser);

        return UserMapper.toGraphQlType(saved);
    }

    @DgsMutation
    public Boolean deleteUser(@InputArgument String userId) {
        long id = parseId(userId, "user");

        if (!userRepository.existsById(id)) {
            throw new EntityNotFoundException(String.format("User with id %s not found", id));
        }
        userRepository.deleteById(id);
        return true;
    }

    @DgsMutation
    @Transactional
    public void updateUserRole(@InputArgument String userId, @InputArgument UpdateRoleInput input) {
        long id = parseId(userId, "user");

        ee.bytecore.backend.entities.user.User user = userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(String.format("User with id %s not found", id)));

        user.setRole(UserInputMapper.mapEnum(UserRole.class, input.getRole()));
    }

    // User address

    @DgsMutation
    public UserAddress addMyAddress(@InputArgument CreateAddressInput input) {
        //TODO implement add address for me
        throw new UnsupportedOperationException("addMyAddress not implemented yet");
    }

    @DgsMutation
    public UserAddress updateMyAddress(@InputArgument String addressId, @InputArgument UpdateAddressInput input) {
        //TODO implement update address for me
        throw new UnsupportedOperationException("updateMyAddress not implemented yet");
    }

    @DgsMutation
    public Boolean deleteMyAddress(@InputArgument String addressId) {
        //TODO implement delete address for me
        throw new UnsupportedOperationException("deleteMyAddress not implemented yet");
    }

    @DgsMutation
    public Boolean deleteUserAddress(@InputArgument String addressId) {
        long id = parseId(addressId, "address");

        if (!userAddressRepository.existsById(id)) {
            throw new EntityNotFoundException(String.format("Address with id %s not found", id));
        }
        userAddressRepository.deleteById(id);
        return true;
    }

    // Payment method

    @DgsMutation
    public UserPaymentMethod addMyPaymentMethod(@InputArgument CreatePaymentMethodInput input) {
        //TODO implement add payment method for me
        throw new UnsupportedOperationException("addMyPaymentMethod not implemented yet");
    }

    @DgsMutation
    public Boolean deleteMyPaymentMethod(@InputArgument String paymentMethodId) {
        //TODO implement delete payment method for me
        throw new UnsupportedOperationException("deleteMyPaymentMethod not implemented yet");
    }

    @DgsMutation
    public UserPaymentMethod addUserPaymentMethod(@InputArgument String userId, @InputArgument CreatePaymentMethodInput input) {
        long id = parseId(userId, "user");
        ee.bytecore.backend.entities.user.User user = userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(String.format("User with id %s not found", id)));
        ee.bytecore.backend.entities.user.UserPaymentMethod newPaymentMethod = UserInputMapper.fromCreateInput(input, user);

        ee.bytecore.backend.entities.user.UserPaymentMethod savedPaymentMethod = userPaymentMethodRepository.save(newPaymentMethod);

        return UserMapper.toGraphQlType(savedPaymentMethod);
    }

    @DgsMutation
    public Boolean deleteUserPaymentMethod(@InputArgument String paymentMethodId) {
        long id = parseId(paymentMethodId, "paymentMethod");
        if (!userPaymentMethodRepository.existsById(id)) {
            throw new EntityNotFoundException(String.format("PaymentMethod with id %s not found", id));
        }
        userPaymentMethodRepository.deleteById(id);
        return true;
    }

    // Helpers

    private long parseId(String rawId, String entityName) {
        try {
            return Long.parseLong(rawId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(String.format("Invalid %s id: %s", entityName, rawId));
        }
    }
}