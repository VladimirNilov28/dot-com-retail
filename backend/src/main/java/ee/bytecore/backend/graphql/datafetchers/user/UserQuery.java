package ee.bytecore.backend.graphql.datafetchers.user;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import ee.bytecore.backend.graphql.mappers.UserMapper;
import ee.bytecore.backend.security.CurrentUserProvider;
import ee.bytecore.backend.services.UserAddressService;
import ee.bytecore.backend.services.UserPaymentMethodService;
import ee.bytecore.backend.services.UserService;

import com.netflix.dgs.codegen.generated.types.User;
import com.netflix.dgs.codegen.generated.types.UserAddress;
import com.netflix.dgs.codegen.generated.types.UserPaymentMethod;
import com.netflix.graphql.dgs.*;

@DgsComponent
public class UserQuery {

    private final UserService userService;
    private final UserAddressService userAddressService;
    private final UserPaymentMethodService userPaymentMethodService;
    private final CurrentUserProvider currentUserProvider;

    public UserQuery(
            UserService userService,
            UserAddressService userAddressService,
            UserPaymentMethodService userPaymentMethodService,
            CurrentUserProvider currentUserProvider) {
        this.userService = userService;
        this.userAddressService = userAddressService;
        this.userPaymentMethodService = userPaymentMethodService;
        this.currentUserProvider = currentUserProvider;
    }

    @DgsQuery
    public User me() {
        // Not resolved via a @CurrentSecurityContext parameter: DGS's
        // @DgsQuery methods don't get Spring MVC's HandlerMethodArgumentResolver
        // bridging for it in this setup, so the annotation silently injects
        // null. Direct SecurityContextHolder access works reliably since
        // root query fields execute synchronously on the request thread.
        Long userId = currentUserProvider.getCurrentUserId();
        if (userId == null) {
            throw new IllegalArgumentException("Unable to resolve current user from authentication");
        }
        return userService.findById(userId).map(UserMapper::toGraphQlType).orElse(null);
    }

    @DgsQuery
    public User user(@InputArgument String id) {
        return userService
                .findById(Long.valueOf(id))
                .map(UserMapper::toGraphQlType)
                .orElse(null);
    }

    @DgsData(parentType = "User")
    public CompletableFuture<List<UserAddress>> addresses(DgsDataFetchingEnvironment dfe) {
        User user = dfe.getSource();
        if (user == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        org.dataloader.DataLoader<Long, List<ee.bytecore.backend.entities.user.UserAddress>> loader =
                dfe.getDataLoader("addressesByUserId");
        return loader.load(Long.valueOf(user.getId()))
                .thenApply(addresses ->
                        addresses.stream().map(UserMapper::toGraphQlType).toList());
    }

    @DgsData(parentType = "User")
    public CompletableFuture<List<UserPaymentMethod>> paymentMethods(DgsDataFetchingEnvironment dfe) {
        User user = dfe.getSource();
        if (user == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        org.dataloader.DataLoader<Long, List<ee.bytecore.backend.entities.user.UserPaymentMethod>> loader =
                dfe.getDataLoader("paymentMethodsByUserId");
        return loader.load(Long.valueOf(user.getId()))
                .thenApply(methods ->
                        methods.stream().map(UserMapper::toGraphQlType).toList());
    }
}

// TODO replace return null with custom exceptions
