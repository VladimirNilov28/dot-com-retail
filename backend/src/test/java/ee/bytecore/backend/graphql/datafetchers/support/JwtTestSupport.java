package ee.bytecore.backend.graphql.datafetchers.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import java.time.Instant;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Builds a {@link RequestPostProcessor} that authenticates a MockMvc request with a
 * JWT carrying a {@code role} claim and/or a {@code scope} claim, run through the
 * real {@link JwtAuthenticationConverter} (not {@code @WithMockUser}, which doesn't
 * survive the real OAuth2 resource server filter chain once {@code SecurityConfig}
 * is imported). Centralizes what was previously duplicated per authorization test
 * class so scope-based tests (SCOPE_* authorities) don't each re-implement JWT
 * construction.
 */
public final class JwtTestSupport {

    private JwtTestSupport() {}

    public static RequestPostProcessor asAuthenticatedJwt(
            JwtAuthenticationConverter jwtAuthenticationConverter, String subject, String role, String... scopes) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));

        if (role != null) {
            builder.claim("role", role);
        }
        if (scopes.length > 0) {
            builder.claim("scope", String.join(" ", scopes));
        }

        return authentication(jwtAuthenticationConverter.convert(builder.build()));
    }
}
