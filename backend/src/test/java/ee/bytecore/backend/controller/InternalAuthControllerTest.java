package ee.bytecore.backend.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ee.bytecore.backend.config.SecurityConfig;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.entities.user.UserRegistrationReservation;
import ee.bytecore.backend.enums.UserRole;
import ee.bytecore.backend.services.UserService;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@WebMvcTest(InternalAuthController.class)
@Import(SecurityConfig.class)
class InternalAuthControllerTest {

    @Autowired
    MockMvc mockMvc;

    ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @MockitoBean
    UserService userService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.create("admin", "admin@bytecore.ee", LocalDate.of(2000, 1, 1));
        user.setId(1L);
        user.setRole(UserRole.ADMIN);
    }

    @Test
    void shouldResolveUserByIdTest() throws Exception {
        when(userService.findById(1L)).thenReturn(Optional.of(user));

        mockMvc.perform(get("/internal/users/{id}", 1L)
                        .with(jwt().jwt(jwt -> jwt.subject("oauth-service-internal")
                                        .claim("client_id", "oauth-service-internal"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_internal:provision-user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.username", is("admin")))
                .andExpect(jsonPath("$.role", is("ADMIN")));
    }

    @Test
    void shouldReturn404WhenResolvingMissingUserTest() throws Exception {
        when(userService.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/internal/users/{id}", 999L)
                        .with(jwt().jwt(jwt -> jwt.subject("oauth-service-internal")
                                        .claim("client_id", "oauth-service-internal"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_internal:provision-user"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRejectMalformedUserIdTest() throws Exception {
        mockMvc.perform(get("/internal/users/{id}", "not-a-number")
                        .with(jwt().jwt(jwt -> jwt.subject("oauth-service-internal")
                                        .claim("client_id", "oauth-service-internal"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_internal:provision-user"))))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void shouldRejectMalformedProvisionRequestBodyTest() throws Exception {
        mockMvc.perform(post("/internal/users")
                        .with(jwt().jwt(jwt -> jwt.subject("oauth-service-internal")
                                        .claim("client_id", "oauth-service-internal"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_internal:provision-user")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not valid json"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void shouldProvisionUserTest() throws Exception {
        when(userService.provision("admin", "admin@bytecore.ee", LocalDate.of(2000, 1, 1), UserRole.ADMIN))
                .thenReturn(user);

        var request = new ProvisionUserRequest("admin", "admin@bytecore.ee", LocalDate.of(2000, 1, 1), UserRole.ADMIN);

        mockMvc.perform(post("/internal/users")
                        .with(jwt().jwt(jwt -> jwt.subject("oauth-service-internal")
                                        .claim("client_id", "oauth-service-internal"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_internal:provision-user")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.username", is("admin")))
                .andExpect(jsonPath("$.role", is("ADMIN")));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "registration-reservations",
                "registration-bind",
                "registration-finalize",
                "registration-maintenance"
            })
    void shouldRejectHumanAndWrongMachineCallerOnNativeEndpointsTest(String endpoint) throws Exception {
        for (String subject : new String[] {"42", "another-machine"}) {
            mockMvc.perform(post("/internal/users/" + endpoint)
                            .with(jwt().jwt(jwt -> jwt.subject(subject).claim("client_id", "oauth-service-internal"))
                                    .authorities(new SimpleGrantedAuthority("SCOPE_internal:provision-user")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/internal/users/" + endpoint)
                        .with(jwt().jwt(jwt -> jwt.subject("oauth-service-internal")
                                        .claim("client_id", "oauth-service-internal"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_user:read")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(userService);
    }

    @Test
    void shouldReserveNativeIdentifiersOnlyForAuthorizedMachineTest() throws Exception {
        var flow = UUID.randomUUID();
        var expiry = java.time.Instant.now().plusSeconds(600);
        var claim = UserRegistrationReservation.create(
                flow, "customer", "customer@example.com", LocalDate.of(1990, 6, 1), expiry);
        when(userService.reserveRegistration(
                        flow, claim.getUsername(), claim.getEmail(), claim.getDateOfBirth(), expiry))
                .thenReturn(claim);
        var request = new InternalAuthController.RegistrationReservationRequest(
                flow, claim.getUsername(), claim.getEmail(), claim.getDateOfBirth(), expiry);
        mockMvc.perform(post("/internal/users/registration-reservations")
                        .with(jwt().jwt(jwt -> jwt.subject("oauth-service-internal")
                                        .claim("client_id", "oauth-service-internal"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_internal:provision-user")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(claim.getId().toString())))
                .andExpect(jsonPath("$.flowId", is(flow.toString())));
    }
}
