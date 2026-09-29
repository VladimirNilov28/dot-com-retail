package ee.bytecore.backend.graphql.datafetchers;

import static ee.bytecore.backend.graphql.datafetchers.support.JwtTestSupport.asAuthenticatedJwt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ee.bytecore.backend.config.SecurityConfig;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.entities.user.UserPaymentMethod;
import ee.bytecore.backend.enums.PaymentMethodType;
import ee.bytecore.backend.enums.UserRole;
import ee.bytecore.backend.graphql.datafetchers.user.UserMutation;
import ee.bytecore.backend.graphql.datafetchers.user.UserQuery;
import ee.bytecore.backend.graphql.scalars.GraphQLConfig;
import ee.bytecore.backend.graphql.scalars.InstantScalar;
import ee.bytecore.backend.graphql.scalars.LocalDateScalar;
import ee.bytecore.backend.repositories.user.UserAddressRepository;
import ee.bytecore.backend.repositories.user.UserPaymentMethodRepository;
import ee.bytecore.backend.repositories.user.UserRepository;
import ee.bytecore.backend.services.UserService;

import com.netflix.graphql.dgs.test.EnableDgsMockMvcTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Characterizes authorization on the admin/support-only User mutations,
 * exercised through the real SecurityFilterChain + JwtAuthenticationConverter
 * (same pattern as MeQueryAuthenticationTest) rather than @WithMockUser,
 * since @WithMockUser's UsernamePasswordAuthenticationToken does not survive
 * the real OAuth2 resource server filter chain imported here.
 */
@SpringBootTest(
        classes = {
            UserQuery.class,
            UserMutation.class,
            GraphQLConfig.class,
            LocalDateScalar.class,
            InstantScalar.class,
            SecurityConfig.class,
            ee.bytecore.backend.security.CurrentUserProvider.class,
            ee.bytecore.backend.services.UserAddressService.class,
            ee.bytecore.backend.services.UserPaymentMethodService.class,
            ee.bytecore.backend.graphql.dataloaders.AddressesByUserIdDataLoader.class,
            ee.bytecore.backend.graphql.dataloaders.PaymentMethodsByUserIdDataLoader.class
        })
@EnableDgsMockMvcTest
@Tag("graphql")
class UserMutationAuthorizationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    UserRepository userRepository;

    @MockitoBean
    UserService userService;

    @MockitoBean
    UserAddressRepository userAddressRepository;

    @MockitoBean
    UserPaymentMethodRepository userPaymentMethodRepository;

    // Never actually invoked: the tests set the SecurityContext directly via
    // the `authentication()` post-processor. Still required as a bean so the
    // resource-server filter chain can be constructed in this narrow slice.
    @MockitoBean
    JwtDecoder jwtDecoder;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.create("target-user", "target-user@example.com", LocalDate.of(1995, 6, 15));
        user.setId(1L);
    }

    @Test
    void shouldRejectDeleteUserForNonAdminTest() throws Exception {
        mockMvc.perform(post("/graphql")
                        .with(asAuthenticatedJwt(jwtAuthenticationConverter, "2", "USER", "user:read"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"mutation { deleteUser(userId: \\\"1\\\") }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldRejectUpdateUserRoleForNonAdminTest() throws Exception {
        when(userService.updateRole(1L, UserRole.ADMIN)).thenReturn(user);

        mockMvc.perform(
                        post("/graphql")
                                .with(asAuthenticatedJwt(jwtAuthenticationConverter, "2", "USER", "user:read"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"query\":\"mutation { updateUserRole(userId: \\\"1\\\", input: { role: ADMIN }) { role } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldRejectDeleteUserAddressForNonAdminTest() throws Exception {
        when(userAddressRepository.existsById(1L)).thenReturn(true);

        mockMvc.perform(
                        post("/graphql")
                                .with(asAuthenticatedJwt(jwtAuthenticationConverter, "2", "USER", "user:read"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"query\":\"mutation { deleteUserAddress(userId: \\\"1\\\", addressId: \\\"1\\\") }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldRejectAddUserPaymentMethodForNonAdminTest() throws Exception {
        UserPaymentMethod paymentMethod = UserPaymentMethod.create(user, "mastercard", PaymentMethodType.CARD);
        paymentMethod.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userPaymentMethodRepository.save(any(UserPaymentMethod.class))).thenReturn(paymentMethod);

        mockMvc.perform(
                        post("/graphql")
                                .with(asAuthenticatedJwt(jwtAuthenticationConverter, "2", "USER", "user:read"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"query\":\"mutation { addUserPaymentMethod(userId: \\\"1\\\", input: { provider: \\\"mastercard\\\", type: CARD }) { type } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldRejectDeleteUserPaymentMethodForNonAdminTest() throws Exception {
        when(userPaymentMethodRepository.existsById(1L)).thenReturn(true);

        mockMvc.perform(
                        post("/graphql")
                                .with(asAuthenticatedJwt(jwtAuthenticationConverter, "2", "USER", "user:read"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"query\":\"mutation { deleteUserPaymentMethod(userId: \\\"1\\\", paymentMethodId: \\\"1\\\") }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldRejectDeleteUserForAdminMissingScopeTest() throws Exception {
        // Regression: ADMIN must NOT bypass a missing user:write scope.
        mockMvc.perform(post("/graphql")
                        .with(asAuthenticatedJwt(jwtAuthenticationConverter, "2", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"mutation { deleteUser(userId: \\\"1\\\") }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldAllowDeleteUserForAdminWithScopeTest() throws Exception {
        mockMvc.perform(post("/graphql")
                        .with(asAuthenticatedJwt(jwtAuthenticationConverter, "2", "ADMIN", "user:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"mutation { deleteUser(userId: \\\"1\\\") }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleteUser").value(true));
    }

    @Test
    void shouldRejectUpdateUserRoleForAdminMissingScopeTest() throws Exception {
        // Regression: ADMIN must NOT bypass a missing user:manage-role scope.
        when(userService.updateRole(1L, UserRole.ADMIN)).thenReturn(user);

        mockMvc.perform(
                        post("/graphql")
                                .with(asAuthenticatedJwt(jwtAuthenticationConverter, "2", "ADMIN"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"query\":\"mutation { updateUserRole(userId: \\\"1\\\", input: { role: ADMIN }) { role } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldAllowUpdateUserRoleForAdminWithScopeTest() throws Exception {
        when(userService.updateRole(1L, UserRole.ADMIN)).thenReturn(user);

        mockMvc.perform(
                        post("/graphql")
                                .with(asAuthenticatedJwt(jwtAuthenticationConverter, "2", "ADMIN", "user:manage-role"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"query\":\"mutation { updateUserRole(userId: \\\"1\\\", input: { role: ADMIN }) { role } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void shouldRejectDeleteUserAddressForAdminMissingScopeTest() throws Exception {
        // Regression: ADMIN must NOT bypass a missing user:write scope.
        mockMvc.perform(
                        post("/graphql")
                                .with(asAuthenticatedJwt(jwtAuthenticationConverter, "2", "ADMIN"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"query\":\"mutation { deleteUserAddress(userId: \\\"1\\\", addressId: \\\"1\\\") }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldRejectAddUserPaymentMethodForAdminMissingScopeTest() throws Exception {
        // Regression: ADMIN must NOT bypass a missing user:write scope.
        mockMvc.perform(
                        post("/graphql")
                                .with(asAuthenticatedJwt(jwtAuthenticationConverter, "2", "ADMIN"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"query\":\"mutation { addUserPaymentMethod(userId: \\\"1\\\", input: { provider: \\\"mastercard\\\", type: CARD }) { type } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldRejectDeleteUserPaymentMethodForAdminMissingScopeTest() throws Exception {
        // Regression: ADMIN must NOT bypass a missing user:write scope.
        mockMvc.perform(
                        post("/graphql")
                                .with(asAuthenticatedJwt(jwtAuthenticationConverter, "2", "ADMIN"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"query\":\"mutation { deleteUserPaymentMethod(userId: \\\"1\\\", paymentMethodId: \\\"1\\\") }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldRejectAddMyAddressMissingWriteScopeTest() throws Exception {
        mockMvc.perform(
                        post("/graphql")
                                .with(asAuthenticatedJwt(jwtAuthenticationConverter, "1", "USER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"query\":\"mutation { addMyAddress(input: { firstName: \\\"A\\\", lastName: \\\"B\\\", addressLine1: \\\"Main St\\\", city: \\\"Tallinn\\\", postalCode: \\\"10111\\\", country: \\\"EE\\\" }) { id } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldRejectMeMissingReadScopeTest() throws Exception {
        mockMvc.perform(post("/graphql")
                        .with(asAuthenticatedJwt(jwtAuthenticationConverter, "1", "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"{ me { id } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldAllowMeWithReadScopeTest() throws Exception {
        User self = User.create("self", "self@example.com", LocalDate.of(1995, 6, 15));
        self.setId(1L);
        when(userService.findById(1L)).thenReturn(Optional.of(self));

        mockMvc.perform(post("/graphql")
                        .with(asAuthenticatedJwt(jwtAuthenticationConverter, "1", "USER", "user:read"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"{ me { id } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.me.id").value("1"));
    }

    @Test
    void shouldRejectUserQueryForRegularUserTest() throws Exception {
        // Closes the IDOR: user:read scope alone must not let a regular USER
        // look up an arbitrary other user's profile.
        mockMvc.perform(post("/graphql")
                        .with(asAuthenticatedJwt(jwtAuthenticationConverter, "2", "USER", "user:read"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"{ user(id: \\\"1\\\") { id } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldRejectUserQueryForAdminMissingScopeTest() throws Exception {
        // Regression: ADMIN must NOT bypass a missing user:read scope.
        mockMvc.perform(post("/graphql")
                        .with(asAuthenticatedJwt(jwtAuthenticationConverter, "2", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"{ user(id: \\\"1\\\") { id } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldAllowUserQueryForAdminWithScopeTest() throws Exception {
        when(userService.findById(1L)).thenReturn(Optional.of(user));

        mockMvc.perform(post("/graphql")
                        .with(asAuthenticatedJwt(jwtAuthenticationConverter, "2", "ADMIN", "user:read"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"{ user(id: \\\"1\\\") { id } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.id").value("1"));
    }

    @Test
    void shouldAllowUserQueryForSupportWithScopeTest() throws Exception {
        when(userService.findById(1L)).thenReturn(Optional.of(user));

        mockMvc.perform(post("/graphql")
                        .with(asAuthenticatedJwt(jwtAuthenticationConverter, "2", "SUPPORT", "user:read"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"{ user(id: \\\"1\\\") { id } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.id").value("1"));
    }
}
