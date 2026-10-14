package ee.bytecore.backend.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
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
import ee.bytecore.backend.integration.HydraClient;
import ee.bytecore.backend.integration.KratosClient;
import ee.bytecore.backend.integration.payment.PaymentOutboxPublisher;
import ee.bytecore.backend.repositories.user.UserRepository;
import ee.bytecore.backend.services.UserService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.kafka.bootstrap-servers=127.0.0.1:1",
            "spring.kafka.listener.auto-startup=false",
            "spring.docker.compose.enabled=false",
            "cart.guest.ttl=PT2S",
            "cart.guest.cleanup-interval=PT1S",
            "cart.guest.merge-receipt-ttl=PT2S"
        })
@Import(PostgresTestConfiguration.class)
@Tag("integration")
@Timeout(30)
class GuestCartLifecycleIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    UserService userService;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PaymentOutboxPublisher outboxPublisher;

    @MockitoBean
    KratosClient kratos;

    @MockitoBean
    HydraClient hydra;

    private final HttpClient client = HttpClient.newHttpClient();
    private User owner;

    private record Guest(long id, String cookie, Instant expiresAt) {}

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        owner = users.save(User.create("guest-lifecycle-" + suffix, suffix + "@example.com", LocalDate.of(1990, 1, 1)));
        when(jwtDecoder.decode(anyString()))
                .thenReturn(Jwt.withTokenValue("guest-lifecycle-fixture")
                        .header("alg", "RS256")
                        .subject(owner.getId().toString())
                        .claim("role", "USER")
                        .claim("scope", "cart:read cart:write")
                        .build());
        when(kratos.findLinkedIdentity(any())).thenReturn(UUID.randomUUID());
    }

    @Test
    void shouldHonorConfiguredLifetimeAndEventuallyCleanExpiredCartTest() throws Exception {
        Guest guest = start();
        assertThat(guest.expiresAt()).isBefore(Instant.now().plusSeconds(3));
        await().atMost(Duration.ofSeconds(8))
                .untilAsserted(() -> assertThat(body(post("{guestCart{id}}", Map.of(), guest.cookie(), false))
                                .at("/errors/0/extensions/errorType")
                                .asString())
                        .isEqualTo("GUEST_CART_UNAVAILABLE"));
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(
                        jdbc.queryForObject("SELECT count(*) FROM carts WHERE id=?", Integer.class, guest.id()))
                .isZero());
        assertThat(body(post("{guestCart{id}}", Map.of(), guest.cookie(), false))
                        .at("/errors/0/extensions/errorType")
                        .asString())
                .isEqualTo("GUEST_CART_UNAVAILABLE");
    }

    @Test
    void shouldNotReapplyMergeAfterReceiptCleanupTest() throws Exception {
        Guest guest = start();
        UUID requestId = UUID.randomUUID();
        assertThat(merge(guest.cookie(), requestId)
                        .at("/data/mergeGuestCart/status")
                        .asString())
                .isEqualTo("MERGED");
        assertThat(jdbc.queryForObject(
                        """
                SELECT count(*) FROM guest_cart_merge_receipts WHERE user_id=? AND request_id=?
                """,
                        Integer.class,
                        owner.getId(),
                        requestId))
                .isEqualTo(1);
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                        """
                SELECT count(*) FROM guest_cart_merge_receipts WHERE user_id=? AND request_id=?
                """,
                        Integer.class,
                        owner.getId(),
                        requestId))
                .isZero());
        assertThat(merge(null, requestId).has("errors")).isTrue();
        assertThat(merge(guest.cookie(), requestId).has("errors")).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carts WHERE user_id=?", Integer.class, owner.getId()))
                .isEqualTo(1);
    }

    @Test
    void shouldRemoveMergeReceiptWhenAccountIsDeletedTest() throws Exception {
        Guest guest = start();
        UUID requestId = UUID.randomUUID();
        assertThat(merge(guest.cookie(), requestId)
                        .at("/data/mergeGuestCart/status")
                        .asString())
                .isEqualTo("MERGED");
        assertThat(jdbc.queryForObject(
                        """
                SELECT count(*) FROM guest_cart_merge_receipts WHERE user_id=? AND request_id=?
                """,
                        Integer.class,
                        owner.getId(),
                        requestId))
                .isEqualTo(1);
        userService.deleteById(owner.getId());
        assertThat(jdbc.queryForObject(
                        """
                SELECT count(*) FROM guest_cart_merge_receipts WHERE user_id=? AND request_id=?
                """,
                        Integer.class,
                        owner.getId(),
                        requestId))
                .isZero();
        assertThat(merge(null, requestId).has("errors")).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carts WHERE user_id=?", Integer.class, owner.getId()))
                .isZero();
    }

    private Guest start() throws Exception {
        var response = post("mutation{startGuestCart{cart{id expiresAt}}}", Map.of(), null, false);
        JsonNode result = body(response);
        assertThat(result.has("errors")).as("GraphQL response: %s", result).isFalse();
        HttpCookie cookie = response.headers().allValues("Set-Cookie").stream()
                .flatMap(value -> HttpCookie.parse(value).stream())
                .filter(value -> "retail_guest_cart".equals(value.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Guest cookie missing"));
        return new Guest(
                Long.parseLong(result.at("/data/startGuestCart/cart/id").asString()),
                cookie.getName() + "=" + cookie.getValue(),
                Instant.parse(result.at("/data/startGuestCart/cart/expiresAt").asString()));
    }

    private JsonNode merge(String cookie, UUID requestId) throws Exception {
        return body(post(
                "mutation($id:UUID!){mergeGuestCart(requestId:$id){status}}",
                Map.of("id", requestId.toString()),
                cookie,
                true));
    }

    private HttpResponse<String> post(String query, Map<String, Object> variables, String cookie, boolean authenticated)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Origin", "http://localhost:3000")
                .header("X-Guest-Cart-Request", "1")
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(Map.of("query", query, "variables", variables))));
        if (cookie != null) builder.header("Cookie", cookie);
        if (authenticated) builder.header("Authorization", "Bearer guest-lifecycle-fixture");
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        return mapper.readTree(response.body());
    }
}
