package ee.bytecore.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.springframework.graphql.server.WebSocketGraphQlRequest;
import org.springframework.graphql.server.WebSocketSessionInfo;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Mono;

@Tag("unit")
class JwtWebSocketInterceptorTest {

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void shouldRestorePreviousSecurityContextAfterEagerChainInvocationTest(boolean fail, boolean authenticated) {
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode("owner"))
                .thenReturn(Jwt.withTokenValue("owner")
                        .header("alg", "RS256")
                        .subject("1")
                        .build());
        JwtWebSocketInterceptor interceptor = new JwtWebSocketInterceptor(decoder, new JwtAuthenticationConverter());
        WebSocketSessionInfo session = mock(WebSocketSessionInfo.class);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        interceptor
                .handleConnectionInitialization(session, Map.of("Authorization", "Bearer owner"))
                .block();
        WebSocketGraphQlRequest request = mock(WebSocketGraphQlRequest.class);
        when(request.getSessionInfo()).thenReturn(session);
        SecurityContext original = SecurityContextHolder.getContext();
        SecurityContext previous = SecurityContextHolder.createEmptyContext();
        Authentication previousAuthentication = authenticated ? mock(Authentication.class) : null;
        previous.setAuthentication(previousAuthentication);
        SecurityContextHolder.setContext(previous);
        try {
            Mono<?> response = interceptor.intercept(request, ignored -> {
                assertThat(SecurityContextHolder.getContext()
                                .getAuthentication()
                                .getName())
                        .isEqualTo("1");
                if (fail) {
                    throw new IllegalStateException("downstream failure");
                }
                return Mono.empty();
            });
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(previousAuthentication);
            if (fail) {
                assertThatThrownBy(response::block)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("downstream failure");
            } else {
                response.block();
            }
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(previousAuthentication);
        } finally {
            SecurityContextHolder.setContext(original);
        }
    }
}
