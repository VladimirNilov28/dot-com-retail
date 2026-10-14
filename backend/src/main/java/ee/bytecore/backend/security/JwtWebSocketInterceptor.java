package ee.bytecore.backend.security;

import java.util.Map;

import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.graphql.server.WebSocketGraphQlInterceptor;
import org.springframework.graphql.server.WebSocketSessionInfo;
import org.springframework.graphql.server.support.BearerTokenAuthenticationExtractor;
import org.springframework.graphql.server.webmvc.AuthenticationWebSocketInterceptor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.concurrent.DelegatingSecurityContextCallable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;

import reactor.core.publisher.Mono;

public class JwtWebSocketInterceptor implements WebSocketGraphQlInterceptor {
    private final AuthenticationWebSocketInterceptor delegate;

    public JwtWebSocketInterceptor(JwtDecoder decoder, JwtAuthenticationConverter converter) {
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(converter);
        delegate = new AuthenticationWebSocketInterceptor(
                new BearerTokenAuthenticationExtractor(), provider::authenticate);
    }

    @Override
    public Mono<Object> handleConnectionInitialization(WebSocketSessionInfo session, Map<String, Object> payload) {
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if ("Authorization".equalsIgnoreCase(entry.getKey())) {
                if (!(entry.getValue() instanceof String value) || value.isBlank()) {
                    return Mono.error(new BadCredentialsException("JWT authentication is required"));
                }
                return delegate.handleConnectionInitialization(session, payload);
            }
        }
        return session.getPrincipal()
                .filter(principal -> principal instanceof Authentication authentication
                        && authentication.isAuthenticated()
                        && !(authentication instanceof AnonymousAuthenticationToken))
                .switchIfEmpty(Mono.error(new BadCredentialsException("JWT authentication is required")))
                .then(Mono.empty());
    }

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, WebGraphQlInterceptor.Chain chain) {
        // DGS MVC eagerly blocks the downstream chain. Defer it until the delegate's
        // authenticated Reactor context exists. Spring Security scopes its context
        // to that invocation and restores the previous context even on failure.
        return delegate.intercept(
                request,
                authenticatedRequest -> Mono.deferContextual(context -> {
                    String contextKey = SecurityContext.class.getName();
                    if (!context.hasKey(contextKey)) {
                        return chain.next(authenticatedRequest);
                    }
                    SecurityContext securityContext = context.get(contextKey);
                    return Mono.fromCallable(new DelegatingSecurityContextCallable<>(
                                    () -> chain.next(authenticatedRequest), securityContext))
                            .flatMap(response -> response);
                }));
    }
}
