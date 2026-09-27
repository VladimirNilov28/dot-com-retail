package ee.bytecore.backend.graphql.datafetchers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

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
                        .with(asAuthenticatedJwt("2", "USER", "user:read"))
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
                                .with(asAuthenticatedJwt("2", "USER", "user:read"))
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
                                .with(asAuthenticatedJwt("2", "USER", "user:read"))
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
                                .with(asAuthenticatedJwt("2", "USER", "user:read"))
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
                                .with(asAuthenticatedJwt("2", "USER", "user:read"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"query\":\"mutation { deleteUserPaymentMethod(userId: \\\"1\\\", paymentMethodId: \\\"1\\\") }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    private RequestPostProcessor asAuthenticatedJwt(String subject, String role, String scope) {
        Jwt jwt = realHydraShapedJwt(subject, role, scope);
        return authentication(jwtAuthenticationConverter.convert(jwt));
    }

    private Jwt realHydraShapedJwt(String subject, String role, String scope) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .claim("role", role)
                .claim("scope", scope)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
