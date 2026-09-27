package ee.bytecore.backend.graphql.datafetchers.user;

import java.util.List;

import com.netflix.dgs.codegen.generated.types.User;
import com.netflix.dgs.codegen.generated.types.UserAddress;
import com.netflix.dgs.codegen.generated.types.UserPaymentMethod;
import ee.bytecore.backend.graphql.mappers.UserMapper;
import ee.bytecore.backend.repositories.user.UserAddressRepository;
import ee.bytecore.backend.repositories.user.UserPaymentMethodRepository;
import ee.bytecore.backend.services.UserService;
import org.springframework.security.core.context.SecurityContextHolder;

import com.netflix.graphql.dgs.*;

@DgsComponent
public class UserQuery {

    private final UserService userService;
    private final UserAddressRepository userAddressRepository;
    private final UserPaymentMethodRepository userPaymentMethodRepository;

    public UserQuery(UserService userService, UserAddressRepository userAddressRepository, UserPaymentMethodRepository userPaymentMethodRepository) {
        this.userService = userService;
        this.userAddressRepository = userAddressRepository;
        this.userPaymentMethodRepository = userPaymentMethodRepository;
    }

    @DgsQuery
    public User me() {
        // Not resolved via a @CurrentSecurityContext parameter: DGS's
        // @DgsQuery methods don't get Spring MVC's HandlerMethodArgumentResolver
        // bridging for it in this setup, so the annotation silently injects
        // null. Direct SecurityContextHolder access works reliably since
        // root query fields execute synchronously on the request thread.
        String sub = SecurityContextHolder.getContext().getAuthentication().getName();
        return userService.findById(Long.valueOf(sub))
                .map(UserMapper::toGraphQlType)
                .orElse(null);
    }

    @DgsQuery
    public User user(@InputArgument String id) {
        return userService.findById(Long.valueOf(id))
                .map(UserMapper::toGraphQlType)
                .orElse(null);
    }

    @DgsData(parentType = "User")
    public List<UserAddress> addresses(DgsDataFetchingEnvironment dfe) {
        User user = dfe.getSource();
        if (user == null) {
            return List.of();
        }
        return userAddressRepository.findAllByUserId(Long.valueOf( user.getId())).stream()
                .map(UserMapper::toGraphQlType)
                .toList();
    }

    @DgsData(parentType = "User")
    public List<UserPaymentMethod> paymentMethods(DgsDataFetchingEnvironment dfe) {
        User user = dfe.getSource();
        if (user == null) {
            return List.of();
        }
        return userPaymentMethodRepository.findAllByUserId(Long.valueOf(user.getId())).stream()
                .map(UserMapper::toGraphQlType)
                .toList();
    }
}

//TODO move business logic and repository manipulations to service
//TODO replace return null with custom exceptions
//TODO implement DataLoaders and solve N+1 problem