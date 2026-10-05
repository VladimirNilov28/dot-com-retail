package ee.bytecore.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

import ee.bytecore.backend.config.SecurityConfig;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.enums.UserRole;
import ee.bytecore.backend.integration.HydraClient;
import ee.bytecore.backend.integration.KratosClient;
import ee.bytecore.backend.repositories.user.UserRepository;
import ee.bytecore.backend.services.UserService;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("unit")
@WebMvcTest(value = InternalAuthController.class, properties = "oauth.service.client-id=bootstrap-test-machine")
@Import({SecurityConfig.class, UserService.class})
class BootstrapAdminAuthorizationTest {

    private static final String BODY =
            """
            {"username":"bootstrap-admin","email":"bootstrap-admin@example.com",
             "dateOfBirth":"2000-01-01","role":"USER"}
            """;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    UserRepository userRepository;

    @MockitoBean
    KratosClient kratosClient;

    @MockitoBean
    HydraClient hydraClient;

    @MockitoBean
    PlatformTransactionManager transactionManager;

    private MockHttpServletRequestBuilder request() {
        return post("/internal/users/bootstrap-admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY);
    }

    private MockHttpServletRequestBuilder machineRequest() {
        return request()
                .with(authentication(jwtAuthenticationConverter.convert(
                        token("bootstrap-test-machine", "bootstrap-test-machine", "internal:provision-user", "USER"))));
    }

    private Jwt token(String subject, String clientId, String scope, String role) {
        return Jwt.withTokenValue("bootstrap-test-token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("client_id", clientId)
                .claim("scope", scope)
                .claim("role", role)
                .build();
    }

    @Test
    void shouldCreateFreshAdminForConfiguredMachineAndIgnoreSuppliedRoleTest() throws Exception {
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(12L);
            return user;
        });
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(machineRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(12))
                .andExpect(jsonPath("$.username").value("bootstrap-admin"))
                .andExpect(jsonPath("$.email").value("bootstrap-admin@example.com"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.password").doesNotExist());

        verify(userRepository, never()).findByUsername(any());
        verifyNoInteractions(kratosClient);
    }

    @Test
    void shouldRejectUnauthenticatedBootstrapTest() throws Exception {
        mockMvc.perform(request()).andExpect(status().isUnauthorized());
        verifyNoInteractions(userRepository, kratosClient);
    }

    @ParameterizedTest
    @CsvSource({
        "42,bytecore-web,internal:provision-user,USER",
        "42,bytecore-web,internal:provision-user,ADMIN",
        "42,bootstrap-test-machine,internal:provision-user,ADMIN",
        "bootstrap-test-machine,bytecore-web,internal:provision-user,ADMIN",
        "other-machine,other-machine,internal:provision-user,ADMIN",
        "bootstrap-test-machine,bootstrap-test-machine,user:write,ADMIN"
    })
    void shouldRejectHumanForeignMachineMismatchedClaimsAndInsufficientScopeTest(
            String subject, String clientId, String scope, String role) throws Exception {
        mockMvc.perform(request()
                        .with(authentication(
                                jwtAuthenticationConverter.convert(token(subject, clientId, scope, role)))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(userRepository, kratosClient);
    }

    @Test
    void shouldRejectMachineMissingClientClaimTest() throws Exception {
        Jwt jwt = Jwt.withTokenValue("bootstrap-test-token")
                .header("alg", "RS256")
                .subject("bootstrap-test-machine")
                .claim("scope", "internal:provision-user")
                .build();
        mockMvc.perform(request().with(authentication(jwtAuthenticationConverter.convert(jwt))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(userRepository, kratosClient);
    }

    @Test
    void shouldRejectPreclaimedUsernameWithoutElevatingOrSavingExistingUserTest() throws Exception {
        User preclaimed = User.create("bootstrap-admin", "attacker@example.com", LocalDate.of(2000, 1, 1));
        when(userRepository.findByUsername("bootstrap-admin")).thenReturn(Optional.of(preclaimed));
        when(userRepository.existsByUsername("bootstrap-admin")).thenReturn(true);

        mockMvc.perform(machineRequest())
                .andExpect(status().isConflict())
                .andExpect(content().string("User with username bootstrap-admin already exists"));

        assertThat(preclaimed.getRole()).isEqualTo(UserRole.USER);
        verify(userRepository, never()).findByUsername(any());
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).saveAndFlush(any());
        verifyNoInteractions(kratosClient);
    }

    @Test
    void shouldRejectPreclaimedEmailWithoutSavingTest() throws Exception {
        when(userRepository.existsByEmail("bootstrap-admin@example.com")).thenReturn(true);
        mockMvc.perform(machineRequest()).andExpect(status().isConflict());
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).saveAndFlush(any());
        verifyNoInteractions(kratosClient);
    }

    @Test
    void shouldTranslateConcurrentCreationCollisionToConflictWithoutLeakingSqlTest() throws Exception {
        when(userRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("private-sql"));
        mockMvc.perform(machineRequest())
                .andExpect(status().isConflict())
                .andExpect(
                        content()
                                .string(
                                        "User with username bootstrap-admin or email bootstrap-admin@example.com already exists"));
        verify(userRepository, never()).findByUsername(any());
        verifyNoInteractions(kratosClient);
    }

    @Test
    void shouldTranslateFlushCollisionToConflictWithoutLeakingSqlTest() throws Exception {
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("private-sql"));
        mockMvc.perform(machineRequest())
                .andExpect(status().isConflict())
                .andExpect(
                        content()
                                .string(
                                        "User with username bootstrap-admin or email bootstrap-admin@example.com already exists"));
        verifyNoInteractions(kratosClient);
    }

    @Test
    void shouldRejectMissingDateOfBirthBeforePersistenceTest() throws Exception {
        mockMvc.perform(
                        machineRequest()
                                .content(
                                        """
                        {"username":"bootstrap-admin","email":"bootstrap-admin@example.com"}
                        """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(userRepository, kratosClient);
    }
}
