package ee.bytecore.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ee.bytecore.backend.exceptions.IdentitySyncException;
import ee.bytecore.backend.exceptions.UserAlreadyExistsException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AccountDeletionClientTest {
    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private HttpServer server;
    private final List<String> calls = new ArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void shouldMatchMetadataRatherThanEmailAndRevokeExactIdentitySessionsTest() throws IOException {
        start(exchange -> {
            calls.add(
                    exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            if (exchange.getRequestURI().getPath().equals("/admin/identities")) {
                respond(
                        exchange,
                        200,
                        "[" + linked(1)
                                + ",{\"id\":\"unrelated\",\"traits\":{\"email\":\"old@example.com\"},\"metadata_admin\":{\"spring_user_id\":2}}]");
            } else if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, linked(1));
            } else respond(exchange, 204, "");
        });
        KratosClient client = new KratosClient(url());
        assertThat(client.findLinkedIdentity(1L)).isEqualTo(ID);
        client.deleteLinkedIdentity(ID, 1L);
        assertThat(calls)
                .containsExactly(
                        "GET /admin/identities",
                        "GET /admin/identities/" + ID,
                        "DELETE /admin/identities/" + ID + "/sessions",
                        "DELETE /admin/identities/" + ID);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "[]",
                "[{\"id\":\"other\",\"traits\":{\"email\":\"owner@example.com\"}}]",
                "[{\"id\":\"other\",\"metadata_admin\":{\"spring_user_id\":2}}]"
            })
    void shouldRejectAbsentOrMismatchedMetadataLinksTest(String body) throws IOException {
        start(exchange -> respond(exchange, 200, body));
        assertThatThrownBy(() -> new KratosClient(url()).findLinkedIdentity(1L))
                .isInstanceOf(IdentitySyncException.class)
                .hasMessageContaining("verified");
    }

    @Test
    void shouldRejectDuplicateLinksTest() throws IOException {
        start(exchange -> respond(exchange, 200, "[" + linked(1) + "," + linked(1) + "]"));
        assertThatThrownBy(() -> new KratosClient(url()).findLinkedIdentity(1L))
                .isInstanceOf(IdentitySyncException.class)
                .hasMessageContaining("Multiple");
    }

    @Test
    void shouldFindLinkOnLaterPageAndRejectDuplicateAcrossPagesTest() throws IOException {
        String page = "[" + String.join(",", java.util.Collections.nCopies(249, "{}")) + "," + linked(1) + "]";
        start(exchange -> {
            boolean first = !exchange.getRequestURI().getQuery().contains("page_token=");
            if (first)
                exchange.getResponseHeaders()
                        .add("Link", "<" + url() + "/admin/identities?page_token=next>; rel=\"next\"");
            respond(exchange, 200, first ? page : "[" + linked(1) + "]");
        });
        assertThatThrownBy(() -> new KratosClient(url()).findLinkedIdentity(1L))
                .isInstanceOf(IdentitySyncException.class)
                .hasMessageContaining("Multiple");
        server.stop(0);
        start(exchange -> {
            boolean first = !exchange.getRequestURI().getQuery().contains("page_token=");
            if (first)
                exchange.getResponseHeaders()
                        .add("Link", "<" + url() + "/admin/identities?page_token=next>; rel=\"next\"");
            respond(
                    exchange,
                    200,
                    first
                            ? "[" + String.join(",", java.util.Collections.nCopies(250, "{}")) + "]"
                            : "[" + linked(1) + "]");
        });
        assertThat(new KratosClient(url()).findLinkedIdentity(1L)).isEqualTo(ID);
    }

    @Test
    void shouldRevalidateStoredLinkBeforeDeletingAndFailClosedOnChangedOwnerTest() throws IOException {
        start(exchange -> {
            calls.add(exchange.getRequestMethod());
            respond(exchange, 200, linked(2));
        });
        assertThatThrownBy(() -> new KratosClient(url()).deleteLinkedIdentity(ID, 1L))
                .isInstanceOf(IdentitySyncException.class)
                .hasMessageContaining("changed");
        assertThat(calls).containsExactly("GET");
    }

    @Test
    void shouldAllowRetryOfAlreadyDeletedVerifiedIdentityTest() throws IOException {
        start(exchange -> respond(exchange, 404, "{\"error\":\"identity absent\"}"));
        new KratosClient(url()).deleteLinkedIdentity(ID, 1L);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/admin/identities/" + "00000000-0000-4000-8000-000000000001",
                "/admin/identities/00000000-0000-4000-8000-000000000001/sessions"
            })
    void shouldFailClosedOnIdentityOrSessionDeletionFailureTest(String failingPath) throws IOException {
        start(exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) respond(exchange, 200, linked(1));
            else
                respond(
                        exchange,
                        exchange.getRequestURI().getPath().equals(failingPath) ? 503 : 204,
                        "private upstream detail");
        });
        assertThatThrownBy(() -> new KratosClient(url()).deleteLinkedIdentity(ID, 1L))
                .isInstanceOf(IdentitySyncException.class)
                .hasMessageContaining("Retry")
                .hasMessageNotContaining("private upstream detail")
                .hasNoCause();
    }

    @Test
    void shouldRejectEmptySuccessResponseTest() throws IOException {
        start(exchange -> respond(exchange, 200, ""));
        assertThatThrownBy(() -> new KratosClient(url()).deleteLinkedIdentity(ID, 1L))
                .isInstanceOf(IdentitySyncException.class);
    }

    @Test
    void shouldRevokeHydraConsentTokenChainsAndLoginSessionsForCanonicalSubjectOnlyTest() throws IOException {
        start(exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, 204, "");
        });
        new HydraClient(url()).revokeUser(42L);
        assertThat(calls)
                .containsExactly(
                        "DELETE /admin/oauth2/auth/sessions/consent?subject=42&all=true",
                        "DELETE /admin/oauth2/auth/sessions/login?subject=42");
    }

    @ParameterizedTest
    @ValueSource(strings = {"consent", "login"})
    void shouldFailClosedOnHydraFailureWithoutLeakingUpstreamBodyTest(String stage) throws IOException {
        start(exchange -> respond(
                exchange, exchange.getRequestURI().getPath().endsWith(stage) ? 500 : 204, "private upstream detail"));
        assertThatThrownBy(() -> new HydraClient(url()).revokeUser(1L))
                .isInstanceOf(IdentitySyncException.class)
                .hasMessageContaining("Retry")
                .hasMessageNotContaining("private upstream detail")
                .hasNoCause();
    }

    @Test
    void shouldFailClosedOnIdentityLookupOutageTest() throws IOException {
        start(exchange -> respond(exchange, 503, "private upstream detail"));
        assertThatThrownBy(() -> new KratosClient(url()).findLinkedIdentity(1L))
                .isInstanceOf(IdentitySyncException.class)
                .hasMessageContaining("Retry")
                .hasMessageNotContaining("private upstream detail")
                .hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "<http://untrusted.invalid/admin/identities?page_token=next>; rel=\"next\"",
                "<http://untrusted.invalid/admin/identities?page=2>; rel=\"next\""
            })
    void shouldRejectNonAdvancingOrInvalidCursorWithoutFollowingExternalHostTest(String link) throws IOException {
        start(exchange -> {
            calls.add("local");
            exchange.getResponseHeaders().add("Link", link);
            respond(exchange, 200, "[]");
        });
        assertThatThrownBy(() -> new KratosClient(url()).findLinkedIdentity(1L))
                .isInstanceOf(IdentitySyncException.class);
        assertThat(calls.size()).isBetween(1, 2);
    }

    @Test
    void shouldRejectMissingCanonicalOrVerifiedIdentityBeforeHttpTest() throws IOException {
        start(exchange -> {
            calls.add("unexpected");
            respond(exchange, 204, "");
        });
        KratosClient client = new KratosClient(url());
        assertThatThrownBy(() -> client.findLinkedIdentity(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.deleteLinkedIdentity(null, 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.deleteLinkedIdentity(ID, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).isEmpty();
    }

    @Test
    void shouldRejectMissingHydraSubjectBeforeHttpRequestTest() throws IOException {
        start(exchange -> {
            calls.add("unexpected");
            respond(exchange, 204, "");
        });
        assertThatThrownBy(() -> new HydraClient(url()).revokeUser(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HydraClient(url()).revokeUser(0L)).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).isEmpty();
    }

    @Test
    void shouldReturnActionableRegistrationConflictWithoutLeakingCredentialsTest() throws IOException {
        start(exchange -> respond(exchange, 409, "private upstream detail"));
        assertThatThrownBy(() -> new KratosClient(url()).createIdentity("example@example.com", "test-value", 1L))
                .isInstanceOf(UserAlreadyExistsException.class)
                .hasMessageContaining("account recovery")
                .hasMessageNotContaining("private upstream detail")
                .hasNoCause();
    }

    private String linked(int userId) {
        return "{\"id\":\"" + ID
                + "\",\"traits\":{\"email\":\"changed@example.com\"},\"metadata_admin\":{\"spring_user_id\":" + userId
                + "}}";
    }

    private void start(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/admin", handler);
        server.start();
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (status == 204 || body.isEmpty()) exchange.sendResponseHeaders(status, -1);
        else {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
        exchange.close();
    }
}
