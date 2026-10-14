package ee.bytecore.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** Real native sessions/refresh grants, only in this test's isolated Ory network. */
@Tag("integration")
class AccountDeletionOryIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static Network network;
    private static PostgreSQLContainer postgres;
    private static GenericContainer<?> kratos;
    private static GenericContainer<?> hydra;
    private final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new CookieManager())
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @BeforeAll
    static void startIsolatedProviders() throws Exception {
        network = Network.newNetwork();
        postgres = new PostgreSQLContainer("postgres:17-alpine")
                .withNetwork(network)
                .withNetworkAliases("account-db");
        postgres.start();
        try (var connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE account_kratos");
            statement.execute("CREATE DATABASE account_hydra");
        }
        String dsn = "postgres://" + postgres.getUsername() + ":" + postgres.getPassword() + "@account-db:5432/";
        migrate("oryd/kratos:v26.2.0", dsn + "account_kratos?sslmode=disable", "migrate", "sql", "-e", "--yes");
        migrate("oryd/hydra:v26.2.0", dsn + "account_hydra?sslmode=disable", "migrate", "sql", "up", "-e", "--yes");
        kratos = new GenericContainer<>("oryd/kratos:v26.2.0")
                .withNetwork(network)
                .withNetworkAliases("kratos")
                .withExposedPorts(4433, 4434)
                .withEnv("DSN", dsn + "account_kratos?sslmode=disable")
                .withEnv("SECRETS_COOKIE", UUID.randomUUID().toString())
                .withEnv("SECRETS_CIPHER", UUID.randomUUID().toString().replace("-", ""))
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("account-deletion/kratos.yml"),
                        "/etc/account-deletion/kratos.yml")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("account-deletion/identity.json"),
                        "/etc/account-deletion/identity.json")
                .withCommand("serve", "--dev", "--config", "/etc/account-deletion/kratos.yml")
                .waitingFor(Wait.forHttp("/health/ready").forPort(4434));
        kratos.start();
        hydra = new GenericContainer<>("oryd/hydra:v26.2.0")
                .withNetwork(network)
                .withNetworkAliases("hydra")
                .withExposedPorts(4444, 4445)
                .withEnv("DSN", dsn + "account_hydra?sslmode=disable")
                .withEnv("SECRETS_SYSTEM", UUID.randomUUID().toString())
                .withEnv("URLS_SELF_ISSUER", "http://hydra:4444/")
                .withEnv("URLS_LOGIN", "http://login.invalid/login")
                .withEnv("URLS_CONSENT", "http://login.invalid/consent")
                .withEnv("STRATEGIES_ACCESS_TOKEN", "jwt")
                .withEnv("LOG_LEVEL", "error")
                .withCommand("serve", "all", "--dev")
                .waitingFor(Wait.forHttp("/health/ready").forPort(4445));
        hydra.start();
    }

    private static void migrate(String image, String dsn, String... command) {
        try (var migration = new GenericContainer<>(image)
                .withNetwork(network)
                .withEnv("DSN", dsn)
                .withCommand(command)
                .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)))) {
            migration.start();
        }
    }

    @AfterAll
    static void stopIsolatedProviders() {
        if (hydra != null) hydra.close();
        if (kratos != null) kratos.close();
        if (postgres != null) postgres.close();
        if (network != null) network.close();
    }

    @Test
    void shouldRejectNativeReloginExistingSessionAndRefreshThenPermitSameEmailRegistrationTest() throws Exception {
        String email = "disposable-" + UUID.randomUUID() + "@example.com";
        String password = UUID.randomUUID() + "-aA1!";
        KratosClient identities = new KratosClient(base(kratos, 4434));
        identities.createIdentity(email, password, 42L);
        var listed = send("GET", base(kratos, 4434) + "/admin/identities?page_size=250", null, Map.of());
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(listed.body())
                        .path(0)
                        .path("metadata_admin")
                        .path("spring_user_id")
                        .asText())
                .isEqualTo("42");
        UUID identityId = identities.findLinkedIdentity(42L);
        JsonNode login = nativeLogin(email, password, 200);
        String sessionToken = login.path("session_token").asText();
        assertThat(sessionToken.isBlank()).isFalse();
        assertThat(send("GET", base(kratos, 4433) + "/sessions/whoami", null, Map.of("X-Session-Token", sessionToken))
                        .statusCode())
                .isEqualTo(200);
        String refreshToken = issueRefreshGrant("42");

        identities.deleteLinkedIdentity(identityId, 42L);
        new HydraClient(base(hydra, 4445)).revokeUser(42L);
        assertThat(send("GET", base(kratos, 4434) + "/admin/identities/" + identityId, null, Map.of())
                        .statusCode())
                .isEqualTo(404);
        nativeLogin(email, password, 400);
        assertThat(send("GET", base(kratos, 4433) + "/sessions/whoami", null, Map.of("X-Session-Token", sessionToken))
                        .statusCode())
                .isEqualTo(401);
        HttpResponse<String> rejected = token(
                Map.of("grant_type", "refresh_token", "refresh_token", refreshToken, "client_id", "disposable-client"));
        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(rejected.body()).path("error").asText()).isEqualTo("invalid_grant");
        assertThat(JSON.readTree(rejected.body()).has("access_token")).isFalse();
        identities.deleteLinkedIdentity(identityId, 42L);
        new HydraClient(base(hydra, 4445)).revokeUser(42L);
        identities.createIdentity(email, password, 43L);
        assertThat(identities.findLinkedIdentity(43L).equals(identityId)).isFalse();
        nativeLogin(email, password, 200);
    }

    private JsonNode nativeLogin(String email, String password, int expectedStatus) throws Exception {
        ((CookieManager) http.cookieHandler().orElseThrow()).getCookieStore().removeAll();
        var flow = send("GET", base(kratos, 4433) + "/self-service/login/api", null, Map.of());
        assertThat(flow.statusCode()).isEqualTo(200);
        String flowId = JSON.readTree(flow.body()).path("id").asText();
        var login = send(
                "POST",
                base(kratos, 4433) + "/self-service/login?flow=" + flowId,
                JSON.writeValueAsString(Map.of("method", "password", "identifier", email, "password", password)),
                Map.of());
        assertThat(login.statusCode()).isEqualTo(expectedStatus);
        return JSON.readTree(login.body());
    }

    private String issueRefreshGrant(String subject) throws Exception {
        String callback = "http://client.invalid/callback";
        var created = send(
                "POST",
                base(hydra, 4445) + "/admin/clients",
                JSON.writeValueAsString(Map.of(
                        "client_id",
                        "disposable-client",
                        "redirect_uris",
                        new String[] {callback},
                        "grant_types",
                        new String[] {"authorization_code", "refresh_token"},
                        "response_types",
                        new String[] {"code"},
                        "scope",
                        "openid offline_access",
                        "token_endpoint_auth_method",
                        "none")),
                Map.of());
        assertThat(created.statusCode()).isEqualTo(201);
        String verifier = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        String challenge = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.UTF_8)));
        var authorize = send(
                "GET",
                base(hydra, 4444) + "/oauth2/auth?"
                        + form(Map.of(
                                "client_id",
                                "disposable-client",
                                "redirect_uri",
                                callback,
                                "response_type",
                                "code",
                                "scope",
                                "openid offline_access",
                                "state",
                                UUID.randomUUID().toString(),
                                "code_challenge",
                                challenge,
                                "code_challenge_method",
                                "S256")),
                null,
                Map.of());
        String loginChallenge = query(location(authorize), "login_challenge");
        var acceptedLogin = send(
                "PUT",
                base(hydra, 4445) + "/admin/oauth2/auth/requests/login/accept?login_challenge="
                        + encode(loginChallenge),
                JSON.writeValueAsString(Map.of("subject", subject, "remember", true)),
                Map.of());
        assertThat(acceptedLogin.statusCode()).isEqualTo(200);
        var consent = send(
                "GET",
                publicRedirect(
                        JSON.readTree(acceptedLogin.body()).path("redirect_to").asText()),
                null,
                Map.of());
        String consentChallenge = query(location(consent), "consent_challenge");
        var acceptedConsent = send(
                "PUT",
                base(hydra, 4445) + "/admin/oauth2/auth/requests/consent/accept?consent_challenge="
                        + encode(consentChallenge),
                JSON.writeValueAsString(
                        Map.of("grant_scope", new String[] {"openid", "offline_access"}, "remember", true)),
                Map.of());
        assertThat(acceptedConsent.statusCode()).isEqualTo(200);
        var codeResponse = send(
                "GET",
                publicRedirect(JSON.readTree(acceptedConsent.body())
                        .path("redirect_to")
                        .asText()),
                null,
                Map.of());
        var issued = token(Map.of(
                "grant_type",
                "authorization_code",
                "code",
                query(location(codeResponse), "code"),
                "redirect_uri",
                callback,
                "client_id",
                "disposable-client",
                "code_verifier",
                verifier));
        assertThat(issued.statusCode()).isEqualTo(200);
        String initialRefresh =
                JSON.readTree(issued.body()).path("refresh_token").asText();
        assertThat(initialRefresh.isBlank()).isFalse();
        var refreshed = token(Map.of(
                "grant_type", "refresh_token", "refresh_token", initialRefresh, "client_id", "disposable-client"));
        assertThat(refreshed.statusCode()).isEqualTo(200);
        String refresh = JSON.readTree(refreshed.body()).path("refresh_token").asText();
        assertThat(refresh.isBlank()).isFalse();
        return refresh;
    }

    private HttpResponse<String> token(Map<String, String> fields) throws Exception {
        return send(
                "POST",
                base(hydra, 4444) + "/oauth2/token",
                form(fields),
                Map.of("Content-Type", "application/x-www-form-urlencoded"));
    }

    private HttpResponse<String> send(String method, String url, String body, Map<String, String> headers)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json");
        if (body != null && !headers.containsKey("Content-Type")) builder.header("Content-Type", "application/json");
        headers.forEach(builder::header);
        builder.method(
                method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String location(HttpResponse<?> response) {
        assertThat(response.statusCode()).isIn(302, 303);
        return response.headers().firstValue("Location").orElseThrow();
    }

    private String publicRedirect(String url) {
        URI uri = URI.create(url);
        return base(hydra, 4444) + uri.getRawPath() + "?" + uri.getRawQuery();
    }

    private static String base(GenericContainer<?> container, int port) {
        return "http://" + container.getHost() + ":" + container.getMappedPort(port);
    }

    private String query(String url, String key) {
        for (String field : URI.create(url).getRawQuery().split("&")) {
            String[] parts = field.split("=", 2);
            if (parts[0].equals(key)) return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
        }
        throw new IllegalStateException("Expected auth redirect parameter is missing");
    }

    private String form(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
