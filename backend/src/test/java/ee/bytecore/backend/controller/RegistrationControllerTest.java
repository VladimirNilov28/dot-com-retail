package ee.bytecore.backend.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ee.bytecore.backend.config.SecurityConfig;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.exceptions.UserAlreadyExistsException;
import ee.bytecore.backend.services.UserService;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Exercises POST /auth/register through the real SecurityFilterChain (same
 * pattern as InternalAuthControllerTest) to prove it is the one
 * intentionally public entry point, and that it structurally cannot be used
 * to escalate role.
 */
@WebMvcTest(RegistrationController.class)
@Import(SecurityConfig.class)
class RegistrationControllerTest {

    @Autowired
    MockMvc mockMvc;

    ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @MockitoBean
    UserService userService;

    @Test
    void shouldRegisterUserWithoutAuthenticationTest() throws Exception {
        User user = User.create("new-customer", "new-customer@example.com", LocalDate.of(1998, 3, 20));
        user.setId(5L);
        when(userService.registerCustomer(
                        "new-customer", "new-customer@example.com", "s3cret-test-pw", LocalDate.of(1998, 3, 20)))
                .thenReturn(user);

        var request = new RegisterUserRequest(
                "new-customer", "new-customer@example.com", "s3cret-test-pw", LocalDate.of(1998, 3, 20));

        // Deliberately no jwt()/Authorization header — proves the endpoint
        // is reachable unauthenticated.
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(5)))
                .andExpect(jsonPath("$.username", is("new-customer")))
                .andExpect(jsonPath("$.email", is("new-customer@example.com")))
                .andExpect(jsonPath("$.role", is("USER")))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void shouldIgnoreClientSuppliedRoleFieldTest() throws Exception {
        User user = User.create("new-customer", "new-customer@example.com", LocalDate.of(1998, 3, 20));
        user.setId(5L);
        when(userService.registerCustomer(
                        "new-customer", "new-customer@example.com", "s3cret-test-pw", LocalDate.of(1998, 3, 20)))
                .thenReturn(user);

        String bodyWithRoleEscalationAttempt =
                """
                {
                  "username": "new-customer",
                  "email": "new-customer@example.com",
                  "password": "s3cret-test-pw",
                  "dateOfBirth": "1998-03-20",
                  "role": "ADMIN"
                }
                """;

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithRoleEscalationAttempt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role", is("USER")));

        // The service is invoked with exactly the 4-arg signature that has
        // no room for a role — the "role":"ADMIN" in the JSON body is
        // silently dropped by Jackson since RegisterUserRequest has no such
        // property.
        verify(userService)
                .registerCustomer(
                        "new-customer", "new-customer@example.com", "s3cret-test-pw", LocalDate.of(1998, 3, 20));
    }

    @Test
    void shouldRejectMalformedRequestBodyTest() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not valid json"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void shouldReturn400WhenServiceRejectsInvalidInputTest() throws Exception {
        when(userService.registerCustomer("", "bad-email", "s3cret-test-pw", LocalDate.of(1998, 3, 20)))
                .thenThrow(new IllegalArgumentException("Username must not be blank"));

        var request = new RegisterUserRequest("", "bad-email", "s3cret-test-pw", LocalDate.of(1998, 3, 20));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnActionable502WhenIdentityProviderFailsTest() throws Exception {
        when(userService.registerCustomer("new-customer", "new@example.com", "test-value", LocalDate.of(1998, 3, 20)))
                .thenThrow(new ee.bytecore.backend.exceptions.IdentitySyncException("Retry registration."));
        var request =
                new RegisterUserRequest("new-customer", "new@example.com", "test-value", LocalDate.of(1998, 3, 20));
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadGateway())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string("Retry registration."));
    }

    @Test
    void shouldReturn409WhenServiceRejectsDuplicateTest() throws Exception {
        when(userService.registerCustomer(
                        "existing-user", "existing@example.com", "s3cret-test-pw", LocalDate.of(1998, 3, 20)))
                .thenThrow(new UserAlreadyExistsException("User with username existing-user already exists"));

        var request = new RegisterUserRequest(
                "existing-user", "existing@example.com", "s3cret-test-pw", LocalDate.of(1998, 3, 20));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }
}
