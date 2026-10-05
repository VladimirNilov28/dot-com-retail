package ee.bytecore.backend.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ee.bytecore.backend.config.SecurityConfig;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.enums.UserRole;
import ee.bytecore.backend.services.UserService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@WebMvcTest(value = InternalAuthController.class, properties = "oauth.service.client-id=totp-test-machine")
@Import(SecurityConfig.class)
class InternalAuthControllerSecurityTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    UserService userService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.create("admin", "admin@bytecore.ee", LocalDate.of(2000, 1, 1));
        user.setId(1L);
        user.setRole(UserRole.ADMIN);
        when(userService.findById(1L)).thenReturn(Optional.of(user));
    }

    @Test
    void shouldRejectUnauthenticatedRequestTest() throws Exception {
        mockMvc.perform(get("/internal/users/{id}", 1L)).andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectInsufficientScopeTest() throws Exception {
        mockMvc.perform(get("/internal/users/{id}", 1L)
                        .with(jwt().jwt(jwt -> jwt.subject("totp-test-machine"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_user:read"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAllowCorrectScopeTest() throws Exception {
        mockMvc.perform(get("/internal/users/{id}", 1L)
                        .with(jwt().jwt(jwt -> jwt.subject("totp-test-machine").claim("client_id", "totp-test-machine"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_internal:provision-user"))))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRejectCustomerWithProvisioningScopeTest() throws Exception {
        mockMvc.perform(get("/internal/users/{id}", 1L)
                        .with(jwt().jwt(jwt -> jwt.subject("42").claim("client_id", "bytecore-web"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_internal:provision-user"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    @Test
    void shouldRejectOtherMachineWithProvisioningScopeTest() throws Exception {
        mockMvc.perform(get("/internal/users/{id}", 1L)
                        .with(jwt().jwt(jwt -> jwt.subject("another-service").claim("client_id", "another-service"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_internal:provision-user"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    @Test
    void shouldRejectCustomerWithMachineClientClaimTest() throws Exception {
        mockMvc.perform(get("/internal/users/{id}", 1L)
                        .with(jwt().jwt(jwt -> jwt.subject("42").claim("client_id", "totp-test-machine"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_internal:provision-user"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    @Test
    void shouldRejectMachineSubjectWithAnotherClientClaimTest() throws Exception {
        mockMvc.perform(get("/internal/users/{id}", 1L)
                        .with(jwt().jwt(jwt -> jwt.subject("totp-test-machine").claim("client_id", "bytecore-web"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_internal:provision-user"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    @Test
    void shouldRejectProvisioningWithoutMachineScopeTest() throws Exception {
        mockMvc.perform(post("/internal/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"username":"customer","email":"customer@example.com",
                                 "dateOfBirth":"2000-01-01","role":"ADMIN"}
                                """)
                        .with(jwt().jwt(jwt -> jwt.subject("totp-test-machine").claim("client_id", "totp-test-machine"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_user:read"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    @Test
    void shouldRejectCustomerRoleEscalationThroughProvisioningTest() throws Exception {
        when(userService.provision("customer", "customer@example.com", LocalDate.of(2000, 1, 1), UserRole.ADMIN))
                .thenReturn(user);

        mockMvc.perform(post("/internal/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"username":"customer","email":"customer@example.com",
                                 "dateOfBirth":"2000-01-01","role":"ADMIN"}
                                """)
                        .with(jwt().jwt(jwt -> jwt.subject("42").claim("client_id", "bytecore-web"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_internal:provision-user"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    @Test
    void shouldAllowConfiguredMachineToProvisionTest() throws Exception {
        when(userService.provision("admin", "admin@bytecore.ee", LocalDate.of(2000, 1, 1), UserRole.ADMIN))
                .thenReturn(user);

        mockMvc.perform(post("/internal/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"username":"admin","email":"admin@bytecore.ee",
                                 "dateOfBirth":"2000-01-01","role":"ADMIN"}
                                """)
                        .with(jwt().jwt(jwt -> jwt.subject("totp-test-machine").claim("client_id", "totp-test-machine"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_internal:provision-user"))))
                .andExpect(status().isOk());
    }
}
