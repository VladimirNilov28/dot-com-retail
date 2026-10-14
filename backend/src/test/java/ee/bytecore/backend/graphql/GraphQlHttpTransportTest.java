package ee.bytecore.backend.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ee.bytecore.backend.config.SecurityConfig;
import ee.bytecore.backend.graphql.scalars.GraphQLConfig;
import ee.bytecore.backend.graphql.scalars.InstantScalar;
import ee.bytecore.backend.graphql.scalars.LocalDateScalar;

import com.netflix.graphql.dgs.test.EnableDgsTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = GraphQlHttpTransportTest.Configuration.class,
        properties = "spring.docker.compose.enabled=false")
@EnableDgsTest
@Tag("graphql")
class GraphQlHttpTransportTest {

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
    @Import({
        SecurityConfig.class,
        GraphQLConfig.class,
        InstantScalar.class,
        LocalDateScalar.class,
        GraphQlRequestExceptionResolver.class
    })
    static class Configuration {}

    @LocalServerPort
    int port;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        when(jwtDecoder.decode(anyString())).thenThrow(new BadJwtException("Invalid test token"));
        doReturn(Jwt.withTokenValue("valid-test-token")
                        .header("alg", "RS256")
                        .subject("1")
                        .claim("role", "USER")
                        .claim("scope", "user:read")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(600))
                        .build())
                .when(jwtDecoder)
                .decode("valid-test-token");
    }

    @Test
    void shouldRejectAnonymousWithoutCreatingSessionTest() throws Exception {
        HttpResponse<String> response = request("{\"query\":\"{ __typename }\"}", null, null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void shouldNotExemptHttpPostWithWebSocketUpgradeHeaderTest() throws Exception {
        String response = upgradeHeaderRequest("POST", "/graphql", null);
        assertThat(response).startsWith("HTTP/1.1 401");
        assertThat(response).doesNotContainIgnoringCase("Set-Cookie:");
    }

    @Test
    void shouldKeepUnmatchedUrlsDeniedWithWebSocketUpgradeHeaderTest() throws Exception {
        String response = upgradeHeaderRequest("GET", "/not-graphql", "valid-test-token");
        assertThat(response).startsWith("HTTP/1.1 403");
        assertThat(response).doesNotContainIgnoringCase("Set-Cookie:");
    }

    @Test
    void shouldRejectInvalidJwtWithoutCreatingSessionTest() throws Exception {
        HttpResponse<String> response = request("{\"query\":\"{ __typename }\"}", "invalid-test-token", null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void shouldNotAuthenticateUsingSessionCookieTest() throws Exception {
        HttpResponse<String> response = request("{\"query\":\"{ __typename }\"}", null, "JSESSIONID=old-session");
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void shouldAcceptJwtWithoutCreatingSessionTest() throws Exception {
        HttpResponse<String> response = request("{\"query\":\"{ __typename }\"}", "valid-test-token", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"__typename\":\"Query\"");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "", "[1,2,3]", "{\"foo\":\"bar\"}", "{\"query\":\"{ me { id }\""})
    void shouldReturnSafeBadRequestForMalformedBodyTest(String body) throws Exception {
        HttpResponse<String> response = request(body, "valid-test-token", null);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"errors\"", "\"message\"");
        assertThat(response.body()).doesNotContain("Exception", "stackTrace", "valid-test-token");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void shouldPreserveGraphQlSyntaxErrorSemanticsTest() throws Exception {
        HttpResponse<String> response = request("{\"query\":\"{ __typename \"}", "valid-test-token", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"errors\"", "Invalid syntax");
    }

    private HttpResponse<String> request(String body, String token, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .header("Content-Type", "application/json")
                .header("Accept", "*/*")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String upgradeHeaderRequest(String method, String path, String token) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            String headers = method + " " + path + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Upgrade: websocket\r\nConnection: close\r\nContent-Length: 0\r\n"
                    + (token == null ? "" : "Authorization: Bearer " + token + "\r\n") + "\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
