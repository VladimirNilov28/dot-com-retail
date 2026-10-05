package ee.bytecore.backend.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.integration.payment.PaymentOutboxPublisher;
import ee.bytecore.backend.repositories.user.UserRepository;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.kafka.bootstrap-servers=127.0.0.1:1",
            "spring.kafka.listener.auto-startup=false",
            "spring.docker.compose.enabled=false"
        })
@Import(PostgresTestConfiguration.class)
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class GuestCartHiveTransportTest {

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PaymentOutboxPublisher outboxPublisher;

    private GenericContainer<?> router;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    void startRouter() throws Exception {
        Testcontainers.exposeHostPorts(port);
        String graph = Files.readString(Path.of("../infrastructure/hive/supergraph.graphql"));
        String routed = graph.replace(
                "http://host.docker.internal:8080/graphql", "http://host.testcontainers.internal:" + port + "/graphql");
        assertThat(routed).isNotEqualTo(graph);
        router = new GenericContainer<>("ghcr.io/graphql-hive/router:0.2.19")
                .withExposedPorts(4000)
                .withCopyToContainer(Transferable.of(routed), "/app/supergraph.graphql")
                .withCopyToContainer(
                        Transferable.of(Files.readString(Path.of("../infrastructure/hive/router.config.yaml"))),
                        "/app/router.config.yaml")
                .waitingFor(Wait.forHttp("/readiness").forPort(4000));
        router.start();
    }

    @AfterAll
    void stopRouter() {
        if (router != null) router.close();
    }

    @Test
    void shouldPropagateGuestCookiePersistCartAndMergeWithJwtThroughHiveTest() throws Exception {
        String suffix = UUID.randomUUID().toString();
        User owner = users.save(User.create("hive-guest-" + suffix, suffix + "@example.com", LocalDate.of(1990, 1, 1)));
        when(jwtDecoder.decode(anyString()))
                .thenReturn(Jwt.withTokenValue("guest-hive-fixture")
                        .header("alg", "RS256")
                        .subject(owner.getId().toString())
                        .claim("role", "USER")
                        .claim("scope", "cart:read cart:write")
                        .build());
        var started = post("mutation{startGuestCart{cart{id} created}}", Map.of(), null, false);
        JsonNode start = success(started);
        HttpCookie credential = started.headers().allValues("Set-Cookie").stream()
                .flatMap(value -> HttpCookie.parse(value).stream())
                .filter(cookie -> "retail_guest_cart".equals(cookie.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Hive did not propagate the guest cookie"));
        assertThat(credential.isHttpOnly()).isTrue();
        assertThat(credential.getPath()).isEqualTo("/graphql");
        String cookie = credential.getName() + "=" + credential.getValue();
        String guestId = start.at("/data/startGuestCart/cart/id").asString();
        assertThat(success(post("{guestCart{id totals{subtotal}}}", Map.of(), cookie, false))
                        .at("/data/guestCart/id")
                        .asString())
                .isEqualTo(guestId);
        assertThat(success(post("{guestCart{id}}", Map.of(), null, false))
                        .at("/data/guestCart")
                        .isNull())
                .isTrue();
        assertThat(post("{myCart{id}}", Map.of(), cookie, false).body()).contains("errors");
        UUID requestId = UUID.randomUUID();
        String mutation = "mutation($id:UUID!){mergeGuestCart(requestId:$id){status cart{id} conflicts{code}}}";
        var merged = post(mutation, Map.of("id", requestId.toString()), cookie, true);
        assertThat(success(merged).at("/data/mergeGuestCart/status").asString()).isEqualTo("MERGED");
        assertThat(merged.headers().allValues("Set-Cookie").stream()
                        .flatMap(value -> HttpCookie.parse(value).stream())
                        .anyMatch(value -> "retail_guest_cart".equals(value.getName()) && value.getMaxAge() == 0))
                .isTrue();
        assertThat(success(post(mutation, Map.of("id", requestId.toString()), null, true))
                        .at("/data/mergeGuestCart/status")
                        .asString())
                .isEqualTo("REPLAYED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carts WHERE user_id=?", Integer.class, owner.getId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carts WHERE id=?", Integer.class, Long.parseLong(guestId)))
                .isZero();
        assertThat(post("{guestCart{id}}", Map.of(), cookie, false).body()).contains("GUEST_CART_UNAVAILABLE");
    }

    private HttpResponse<String> post(String query, Map<String, Object> variables, String cookie, boolean authenticated)
            throws Exception {
        var builder = HttpRequest.newBuilder(
                        URI.create("http://" + router.getHost() + ":" + router.getMappedPort(4000) + "/graphql"))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Origin", "http://localhost:3000")
                .header("X-Guest-Cart-Request", "1")
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(Map.of("query", query, "variables", variables))));
        if (cookie != null) builder.header("Cookie", cookie);
        if (authenticated) builder.header("Authorization", "Bearer guest-hive-fixture");
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode success(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.has("errors")).as("Hive GraphQL response: %s", body).isFalse();
        return body;
    }
}
