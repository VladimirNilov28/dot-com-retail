package ee.bytecore.backend.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.server.WebSocketGraphQlInterceptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.function.SingletonSupplier;
import org.springframework.web.client.RestTemplate;

import ee.bytecore.backend.security.JwtWebSocketInterceptor;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(3);

    // Boot's autoconfigured JwtDecoder defers Hydra issuer discovery to the
    // first authenticated request (SupplierJwtDecoder), which is the behavior
    // we want to keep — the app must still start when Hydra is down. But it
    // builds the underlying RestTemplate with no connect/read timeout, so a
    // slow/unreachable Hydra hangs that first request indefinitely instead of
    // failing clearly. Rebuild the same lazy-supplier + issuer-discovery
    // decoder explicitly, with bounded timeouts on the discovery call.
    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(DISCOVERY_TIMEOUT);
        requestFactory.setReadTimeout(DISCOVERY_TIMEOUT);
        var restTemplate = new RestTemplate(requestFactory);

        Supplier<JwtDecoder> decoderSupplier = SingletonSupplier.of(() -> NimbusJwtDecoder.withIssuerLocation(issuerUri)
                .restOperations(restTemplate)
                .build());

        return new SupplierJwtDecoder(decoderSupplier);
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        var scopeConverter = new JwtGrantedAuthoritiesConverter();
        var converter = new JwtAuthenticationConverter();

        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var result = new ArrayList<GrantedAuthority>();

            var scopes = scopeConverter.convert(jwt);
            if (scopes != null) {
                result.addAll(scopes);
            }

            String role = jwt.getClaimAsString("role");

            if (role != null) {
                result.add(new SimpleGrantedAuthority("ROLE_" + role));
            }

            return result;
        });

        return converter;
    }

    @Bean
    WebSocketGraphQlInterceptor jwtWebSocketInterceptor(JwtDecoder decoder, JwtAuthenticationConverter converter) {
        return new JwtWebSocketInterceptor(decoder, converter);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {

        return http
                // Stateless Bearer-token resource server — no cookies/session
                // to forge, so CSRF protection (which targets cookie-based
                // browser auth) doesn't apply and would otherwise 403 every
                // POST (GraphQL queries/mutations, internal API) regardless
                // of a valid JWT.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(
                        oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .authorizeHttpRequests(auth -> auth
                        // The one intentionally public entry point: normal
                        // end-user self-registration. Scoped to this exact
                        // path/method only — every other REST and GraphQL
                        // operation below remains authenticated.
                        .requestMatchers(HttpMethod.POST, "/auth/register")
                        .permitAll()
                        // Only the upgrade is public; connection_init must authenticate
                        // through JwtWebSocketInterceptor before any operation executes.
                        .requestMatchers(request -> "GET".equals(request.getMethod())
                                && "/graphql".equals(request.getServletPath())
                                && "websocket".equalsIgnoreCase(request.getHeader("Upgrade")))
                        .permitAll()
                        .requestMatchers("/graphql")
                        .authenticated()
                        .requestMatchers("/internal/**")
                        .authenticated()
                        .anyRequest()
                        .denyAll())
                .build();
    }
}
