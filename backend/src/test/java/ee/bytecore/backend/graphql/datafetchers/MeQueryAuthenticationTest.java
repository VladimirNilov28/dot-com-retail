package ee.bytecore.backend.graphql.datafetchers;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import ee.bytecore.backend.config.SecurityConfig;
import ee.bytecore.backend.entities.user.User;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real SecurityFilterChain + JwtAuthenticationConverter +
 * current-user resolution end-to-end for the {@code me} query — the
 * exact path a real Hydra JWT takes through the Resource Server. Uses raw
 * MockMvc (not GraphQlTester) since this specifically verifies low-level
 * HTTP/security behavior, not the GraphQL contract itself.
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
class MeQueryAuthenticationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    UserService userService;

    @MockitoBean
    UserRepository userRepository;

    // Never actually invoked: the tests set the SecurityContext directly via
    // the `authentication()` post-processor, bypassing real JWT decoding.
    // Still required as a bean so the resource-server filter chain, which
    // depends on a JwtDecoder, can be constructed at all in this narrow
    // (non-auto-configured) test slice.
    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    UserAddressRepository userAddressRepository;

    @MockitoBean
    UserPaymentMethodRepository userPaymentMethodRepository;

    @Test
    void shouldMapJwtSubjectAndRoleToAuthenticationNameAndAuthorities() {
        Jwt jwt = realHydraShapedJwt("1", "ADMIN", "user:read");

        Authentication authentication = jwtAuthenticationConverter.convert(jwt);

        assertThat(authentication.getName()).isEqualTo("1");
        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .contains("ROLE_ADMIN", "SCOPE_user:read");
    }

    @Test
    void shouldResolveMeFromRealJwtSubjectThroughGraphQl() throws Exception {
        User user = User.create("admin", "admin@bytecore.ee", LocalDate.of(2000, 1, 1));
        user.setId(1L);
        user.setRole(UserRole.ADMIN);
        when(userService.findById(1L)).thenReturn(Optional.of(user));

        mockMvc.perform(post("/graphql")
                        .with(asAuthenticatedJwt("1", "ADMIN", "user:read"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"{ me { id username email role } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.me.id").value("1"))
                .andExpect(jsonPath("$.data.me.username").value("admin"))
                .andExpect(jsonPath("$.data.me.role").value("ADMIN"));
    }

    @Test
    void shouldRejectMeQueryWithoutJwt() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"{ me { id } }\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectUserQueryWithoutJwt() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"{ user(id: \\\"1\\\") { id } }\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldMapAbsentRoleClaimToScopesOnlyTest() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("1")
                .claim("scope", "user:read")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        Authentication authentication = jwtAuthenticationConverter.convert(jwt);

        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .contains("SCOPE_user:read")
                .noneMatch(authority -> authority.startsWith("ROLE_"));
    }

    @Test
    void shouldMapScpClaimToScopeAuthoritiesTest() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("1")
                .claim("scp", java.util.List.of("user:read", "product:read"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        Authentication authentication = jwtAuthenticationConverter.convert(jwt);

        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .contains("SCOPE_user:read", "SCOPE_product:read");
    }

    @Test
    void shouldReturnGraphQlErrorRatherThanCrashForNonNumericSubjectTest() throws Exception {
        mockMvc.perform(post("/graphql")
                        .with(asAuthenticatedJwt("not-a-number", "USER", "user:read"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"{ me { id } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").exists());
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
