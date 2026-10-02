package ee.bytecore.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import ee.bytecore.backend.exceptions.IdentitySyncException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real HTTP call KratosClient makes against Kratos's Admin API,
 * using a JDK-native HttpServer stub rather than a mocking framework:
 * KratosClient builds its own RestClient internally from a base-URL string,
 * so there's no injectable RestClient/builder to bind a mock server to.
 */
class KratosClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldSyncEmailWhenIdentityExistsTest() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/admin/identities", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "[{\"id\":\"identity-1\"}]");
            } else {
                respond(exchange, 200, "");
            }
        });
        server.start();
        KratosClient kratosClient = new KratosClient(baseUrl());

        kratosClient.updateIdentityEmail("old@example.com", "new@example.com");
    }

    @Test
    void shouldThrowIdentitySyncExceptionWhenNoIdentityFoundTest() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/admin/identities", exchange -> respond(exchange, 200, "[]"));
        server.start();
        KratosClient kratosClient = new KratosClient(baseUrl());

        assertThatThrownBy(() -> kratosClient.updateIdentityEmail("missing@example.com", "new@example.com"))
                .isInstanceOf(IdentitySyncException.class)
                .hasMessageContaining("missing@example.com");
    }

    @Test
    void shouldWrapHttpFailureAsIdentitySyncExceptionTest() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/admin/identities", exchange -> respond(exchange, 500, ""));
        server.start();
        KratosClient kratosClient = new KratosClient(baseUrl());

        assertThatThrownBy(() -> kratosClient.updateIdentityEmail("old@example.com", "new@example.com"))
                .isInstanceOf(IdentitySyncException.class);
    }

    @Test
    void shouldCreateIdentityLinkedToSpringUserTest() throws IOException {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/admin/identities", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                capturedBody.set(readBody(exchange));
                respond(exchange, 201, "{\"id\":\"identity-1\"}");
            } else {
                respond(exchange, 200, "");
            }
        });
        server.start();
        KratosClient kratosClient = new KratosClient(baseUrl());

        kratosClient.createIdentity("new@example.com", "s3cret-test-pw", 42L);

        String body = capturedBody.get();
        assertThat(body).contains("\"schema_id\":\"default\"");
        assertThat(body).contains("\"email\":\"new@example.com\"");
        assertThat(body).contains("\"password\":\"s3cret-test-pw\"");
        assertThat(body).contains("\"spring_user_id\":42");
    }

    @Test
    void shouldWrapHttpFailureAsIdentitySyncExceptionWhenCreatingIdentityTest() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/admin/identities", exchange -> respond(exchange, 500, ""));
        server.start();
        KratosClient kratosClient = new KratosClient(baseUrl());

        assertThatThrownBy(() -> kratosClient.createIdentity("new@example.com", "s3cret-test-pw", 42L))
                .isInstanceOf(IdentitySyncException.class);
    }

    private String readBody(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        exchange.getRequestBody().transferTo(buffer);
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
        exchange.close();
    }
}
