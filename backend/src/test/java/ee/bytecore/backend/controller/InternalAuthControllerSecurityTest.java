package ee.bytecore.backend.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Optional;

import ee.bytecore.backend.config.SecurityConfig;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.enums.UserRole;
import ee.bytecore.backend.services.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;

@WebMvcTest(InternalAuthController.class)
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
        mockMvc.perform(get("/internal/users/{id}", 1L))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectInsufficientScopeTest() throws Exception {
        mockMvc.perform(get("/internal/users/{id}", 1L)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_user:read"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAllowCorrectScopeTest() throws Exception {
        mockMvc.perform(get("/internal/users/{id}", 1L)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_internal:provision-user"))))
                .andExpect(status().isOk());
    }
}
