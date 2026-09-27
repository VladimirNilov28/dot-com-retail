package ee.bytecore.backend.graphql.datafetchers.user;

import org.springframework.security.access.prepost.PreAuthorize;

import ee.bytecore.backend.enums.UserRole;
import ee.bytecore.backend.graphql.mappers.UserInputMapper;
import ee.bytecore.backend.graphql.mappers.UserMapper;
import ee.bytecore.backend.repositories.user.UserPaymentMethodRepository;
import ee.bytecore.backend.repositories.user.UserRepository;
import ee.bytecore.backend.security.CurrentUserProvider;
import ee.bytecore.backend.services.UserAddressService;
import ee.bytecore.backend.services.UserPaymentMethodService;
import ee.bytecore.backend.services.UserService;

import com.netflix.dgs.codegen.generated.types.*;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import jakarta.persistence.EntityNotFoundException;

@DgsComponent
public class UserMutation {
    private final UserRepository userRepository;
    private final UserService userService;
    private final UserPaymentMethodRepository userPaymentMethodRepository;
    private final UserAddressService userAddressService;
    private final UserPaymentMethodService userPaymentMethodService;
    private final CurrentUserProvider currentUserProvider;

    public UserMutation(
            UserRepository userRepository,
            UserService userService,
            UserPaymentMethodRepository userPaymentMethodRepository,
            UserAddressService userAddressService,
            UserPaymentMethodService userPaymentMethodService,
            CurrentUserProvider currentUserProvider) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.userPaymentMethodRepository = userPaymentMethodRepository;
        this.userAddressService = userAddressService;
        this.userPaymentMethodService = userPaymentMethodService;
        this.currentUserProvider = currentUserProvider;
    }

    // User

    @DgsMutation
    @PreAuthorize("hasRole('ADMIN')")
    public Boolean deleteUser(@InputArgument String userId) {
        long id = parseId(userId, "user");

        userService.deleteById(id);
        return true;
    }

    @DgsMutation
    @PreAuthorize("hasRole('ADMIN')")
    public User updateUserRole(@InputArgument String userId, @InputArgument UpdateRoleInput input) {
        long id = parseId(userId, "user");

        ee.bytecore.backend.entities.user.User updated =
                userService.updateRole(id, UserInputMapper.mapEnum(UserRole.class, input.getRole()));

        return UserMapper.toGraphQlType(updated);
    }

    // User address

    @DgsMutation
    public UserAddress addMyAddress(@InputArgument CreateAddressInput input) {
        long userId = currentUser();
        ee.bytecore.backend.entities.user.User user = userRepository
                .findById(userId)
                .orElseThrow(() -> new EntityNotFoundException(String.format("User with id %s not found", userId)));

        ee.bytecore.backend.entities.user.UserAddress created =
                userAddressService.create(UserInputMapper.fromCreateInput(input, user));
        return UserMapper.toGraphQlType(created);
    }

    @DgsMutation
    public UserAddress updateMyAddress(@InputArgument String addressId, @InputArgument UpdateAddressInput input) {
        long userId = currentUser();
        long id = parseId(addressId, "address");

        ee.bytecore.backend.entities.user.UserAddress owned = userAddressService.findOwned(userId, id);
        UserInputMapper.applyUpdate(input, owned);
        ee.bytecore.backend.entities.user.UserAddress saved = userAddressService.save(owned);
        return UserMapper.toGraphQlType(saved);
    }

    @DgsMutation
    public Boolean deleteMyAddress(@InputArgument String addressId) {
        long userId = currentUser();
        long id = parseId(addressId, "address");

        return userAddressService.deleteOwned(userId, id);
    }

    @DgsMutation
    @PreAuthorize("hasRole('ADMIN')")
    public Boolean deleteUserAddress(@InputArgument String userId, @InputArgument String addressId) {
        long uId = parseId(userId, "user");
        long id = parseId(addressId, "address");

        return userAddressService.deleteOwned(uId, id);
    }

    // Payment method

    @DgsMutation
    public UserPaymentMethod addMyPaymentMethod(@InputArgument CreatePaymentMethodInput input) {
        long userId = currentUser();
        ee.bytecore.backend.entities.user.User user = userRepository
                .findById(userId)
                .orElseThrow(() -> new EntityNotFoundException(String.format("User with id %s not found", userId)));

        ee.bytecore.backend.entities.user.UserPaymentMethod created =
                userPaymentMethodService.create(UserInputMapper.fromCreateInput(input, user));
        return UserMapper.toGraphQlType(created);
    }

    @DgsMutation
    public Boolean deleteMyPaymentMethod(@InputArgument String paymentMethodId) {
        long userId = currentUser();
        long id = parseId(paymentMethodId, "paymentMethod");

        return userPaymentMethodService.deleteOwned(userId, id);
    }

    @DgsMutation
    @PreAuthorize("hasRole('ADMIN')")
    public UserPaymentMethod addUserPaymentMethod(
            @InputArgument String userId, @InputArgument CreatePaymentMethodInput input) {
        long id = parseId(userId, "user");
        ee.bytecore.backend.entities.user.User user = userRepository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(String.format("User with id %s not found", id)));
        ee.bytecore.backend.entities.user.UserPaymentMethod newPaymentMethod =
                UserInputMapper.fromCreateInput(input, user);

        ee.bytecore.backend.entities.user.UserPaymentMethod savedPaymentMethod =
                userPaymentMethodRepository.save(newPaymentMethod);

        return UserMapper.toGraphQlType(savedPaymentMethod);
    }

    @DgsMutation
    @PreAuthorize("hasRole('ADMIN')")
    public Boolean deleteUserPaymentMethod(@InputArgument String userId, @InputArgument String paymentMethodId) {
        long uId = parseId(userId, "user");
        long id = parseId(paymentMethodId, "paymentMethod");

        return userPaymentMethodService.deleteOwned(uId, id);
    }

    // Helpers

    private long currentUser() {
        Long userId = currentUserProvider.getCurrentUserId();
        if (userId == null) {
            throw new IllegalArgumentException("Unable to resolve current user from authentication");
        }
        return userId;
    }

    private long parseId(String rawId, String entityName) {
        try {
            return Long.parseLong(rawId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(String.format("Invalid %s id: %s", entityName, rawId));
        }
    }
}
