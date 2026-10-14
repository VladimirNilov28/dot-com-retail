package ee.bytecore.backend.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.function.SingletonSupplier;
import org.springframework.web.client.RestTemplate;

import ee.bytecore.backend.security.GuestCartHttpFilter;
import ee.bytecore.backend.security.HydraTokenIntrospector;
import ee.bytecore.backend.security.JwtWebSocketInterceptor;
import ee.bytecore.backend.security.ratelimit.RateLimitFilter;

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
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String jwkSetUri,
            ObjectProvider<HydraTokenIntrospector> introspector) {
        return buildJwtDecoder(issuerUri, jwkSetUri, introspector.getIfAvailable());
    }

    // Local checks (signature, issuer, expiry) run first; only a token that passes
    // them is introspected at Hydra, so revoked tokens are rejected (401) even when
    // sent straight to Spring. See docs/auth/browser-contract.md "Token revocation".
    static JwtDecoder buildJwtDecoder(String issuerUri, String jwkSetUri, HydraTokenIntrospector introspector) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(DISCOVERY_TIMEOUT);
        requestFactory.setReadTimeout(DISCOVERY_TIMEOUT);
        var restTemplate = new RestTemplate(requestFactory);

        Supplier<JwtDecoder> decoderSupplier = SingletonSupplier.of(() -> {
            // Containers fetch keys internally while validating the public issuer.
            NimbusJwtDecoder decoder = jwkSetUri.isBlank()
                    ? NimbusJwtDecoder.withIssuerLocation(issuerUri)
                            .restOperations(restTemplate)
                            .build()
                    : NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                            .restOperations(restTemplate)
                            .build();
            OAuth2TokenValidator<Jwt> local = JwtValidators.createDefaultWithIssuer(issuerUri);
            decoder.setJwtValidator(
                    introspector == null
                            ? local
                            : jwt -> {
                                OAuth2TokenValidatorResult result = local.validate(jwt);
                                return result.hasErrors() ? result : introspector.validate(jwt);
                            });
            return decoder;
        });

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

            // Staff roles are honoured only for tokens issued from an AAL2 session.
            // The claim is minted by the OAuth bridge from Kratos' own session
            // assurance; a missing or lower value withholds the staff role (fail closed).
            if (role != null) {
                boolean staff = !"USER".equals(role);
                if (!staff || "aal2".equals(jwt.getClaimAsString("aal"))) {
                    result.add(new SimpleGrantedAuthority("ROLE_" + role));
                } else {
                    result.add(new SimpleGrantedAuthority("MFA_REQUIRED"));
                }
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
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            ObjectProvider<GuestCartHttpFilter> guestFilters,
            ObjectProvider<RateLimitFilter> rateLimitFilters)
            throws Exception {

        // Rate limiting runs after bearer authentication (to key by verified JWT
        // subject) and before guest-cart handling and every handler.
        RateLimitFilter rateLimitFilter = rateLimitFilters.getIfAvailable();
        if (rateLimitFilter != null) http.addFilterAfter(rateLimitFilter, BearerTokenAuthenticationFilter.class);
        GuestCartHttpFilter guestFilter = guestFilters.getIfAvailable();
        if (guestFilter != null) http.addFilterAfter(guestFilter, BearerTokenAuthenticationFilter.class);
        return http
                // Stateless Bearer-token resource server — no cookies/session
                // to forge, so CSRF protection (which targets cookie-based
                // browser auth) doesn't apply to Bearer auth. Guest cart cookie
                // operations have separate Origin + preflight-header protection.
                // The global token-session CSRF filter would otherwise 403 every
                // POST (GraphQL queries/mutations, internal API) regardless
                // of a valid JWT.
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers.addHeaderWriter((request, response) -> {
                    if ("/graphql".equals(request.getServletPath())
                            && !Boolean.TRUE.equals(
                                    request.getAttribute(GuestCartHttpFilter.PUBLIC_CATALOG_CACHEABLE_ATTRIBUTE)))
                        response.setHeader("Cache-Control", "private, no-store");
                }))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(
                        oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                HttpMethod.GET,
                                "/actuator/health",
                                "/actuator/health/liveness",
                                "/actuator/health/readiness")
                        .permitAll()
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
                        .requestMatchers(request -> guestFilter != null
                                && "POST".equals(request.getMethod())
                                && "/graphql".equals(request.getServletPath()))
                        .permitAll()
                        .requestMatchers("/graphql")
                        .authenticated()
                        .requestMatchers("/internal/**")
                        .authenticated()
                        // Public delivery of registered product images only; the
                        // controller serves nothing outside product_images.
                        .requestMatchers(HttpMethod.GET, "/media/products/*")
                        .permitAll()
                        .requestMatchers("/admin/media/**")
                        .authenticated()
                        .anyRequest()
                        .denyAll())
                .build();
    }
}
