package ee.bytecore.backend.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_internal:provision-user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.username", is("admin")))
                .andExpect(jsonPath("$.role", is("ADMIN")));
    }

    @Test
    void shouldReturn404WhenResolvingMissingUserTest() throws Exception {
        when(userService.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/internal/users/{id}", 999L)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_internal:provision-user"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRejectMalformedUserIdTest() throws Exception {
        mockMvc.perform(get("/internal/users/{id}", "not-a-number")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_internal:provision-user"))))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void shouldRejectMalformedProvisionRequestBodyTest() throws Exception {
        mockMvc.perform(post("/internal/users")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_internal:provision-user")))
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
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_internal:provision-user")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.username", is("admin")))
                .andExpect(jsonPath("$.role", is("ADMIN")));
    }
}
