package ee.bytecore.backend.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.integration.payment.PaymentOutboxPublisher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises the real filter chain and the PostgreSQL bucket store. The test
 * client connects from loopback, which is a trusted proxy here, so each test
 * picks its own client address via X-Forwarded-For.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.kafka.bootstrap-servers=127.0.0.1:1",
            "spring.kafka.listener.auto-startup=false",
            "spring.docker.compose.enabled=false",
            "rate-limit.enabled=true",
            "rate-limit.trusted-proxies=127.0.0.1/32,::1/128",
            "rate-limit.policies.general.capacity=3",
            "rate-limit.policies.general.refill-tokens=1",
            "rate-limit.policies.general.refill-period=PT1S",
            "rate-limit.policies.proxy.capacity=3",
            "rate-limit.policies.proxy.refill-tokens=1",
            "rate-limit.policies.proxy.refill-period=PT1S",
            "rate-limit.policies.contact.capacity=1",
            "rate-limit.policies.contact.refill-tokens=1",
            "rate-limit.policies.contact.refill-period=PT1H",
            "rate-limit.policies.contact.operations=submitContactMessage"
        })
@Import(PostgresTestConfiguration.class)
@Tag("integration")
@Timeout(60)
class RateLimitIntegrationTest {
    static final String CATALOG = "{\"query\":\"{ categories { id } }\"}";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper mapper;

    @Autowired
    PostgresRateLimiter limiter;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PaymentOutboxPublisher outboxPublisher;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void resetBuckets() {
        jdbc.update("DELETE FROM rate_limit_buckets");
    }

    HttpResponse<String> post(String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void shouldReturn429AfterBurstAndRecoverAfterRefillTest() throws Exception {
        Map<String, String> client = Map.of("X-Forwarded-For", "198.51.100.10");
        for (int i = 0; i < 3; i++)
            assertThat(post(CATALOG, client).statusCode()).isEqualTo(200);

        HttpResponse<String> limited = post(CATALOG, client);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).hasValue("1");
        assertThat(limited.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode error = mapper.readTree(limited.body()).at("/errors/0");
        assertThat(error.at("/extensions/code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(error.at("/extensions/policy").asString()).isEqualTo("general");
        assertThat(error.at("/extensions/retryAfterSeconds").asInt()).isEqualTo(1);
        assertThat(error.path("message").asString()).contains("Too many requests");

        Thread.sleep(1_100);
        assertThat(post(CATALOG, client).statusCode()).isEqualTo(200);
    }

    @Test
    void shouldIsolateBucketsPerClientAndSubjectTest() throws Exception {
        Map<String, String> first = Map.of("X-Forwarded-For", "198.51.100.20");
        for (int i = 0; i < 3; i++) post(CATALOG, first);
        assertThat(post(CATALOG, first).statusCode()).isEqualTo(429);

        assertThat(post(CATALOG, Map.of("X-Forwarded-For", "198.51.100.21")).statusCode())
                .isEqualTo(200);

        when(jwtDecoder.decode("token-42"))
                .thenReturn(Jwt.withTokenValue("token-42")
                        .header("alg", "RS256")
                        .subject("42")
                        .claim("role", "USER")
                        .claim("scope", "")
                        .build());
        Map<String, String> authenticated =
                Map.of("X-Forwarded-For", "198.51.100.20", "Authorization", "Bearer token-42");
        for (int i = 0; i < 3; i++)
            assertThat(post(CATALOG, authenticated).statusCode()).isEqualTo(200);
        assertThat(post(CATALOG, authenticated).statusCode()).isEqualTo(429);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rate_limit_buckets WHERE bucket_key = 'general|sub:42'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void shouldNotLetPrependedForwardedForEntriesEscapeTheBucketTest() throws Exception {
        for (int i = 0; i < 3; i++)
            assertThat(post(CATALOG, Map.of("X-Forwarded-For", "10.0.0." + i + ", 198.51.100.30"))
                            .statusCode())
                    .isEqualTo(200);

        assertThat(post(CATALOG, Map.of("X-Forwarded-For", "10.9.9.9, 198.51.100.30"))
                        .statusCode())
                .isEqualTo(429);
    }

    @Test
    void shouldApplyOperationPolicyOnTopOfGeneralPolicyTest() throws Exception {
        Map<String, String> client = Map.of("X-Forwarded-For", "198.51.100.40");
        String contact = "{\"query\":\"mutation { submitContactMessage(input: {}) { accepted } }\"}";
        assertThat(post(contact, client).statusCode()).isNotEqualTo(429);

        HttpResponse<String> limited = post(contact, client);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(mapper.readTree(limited.body())
                        .at("/errors/0/extensions/policy")
                        .asString())
                .isEqualTo("contact");
        assertThat(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow()))
                .isBetween(3_590L, 3_600L);
        assertThat(post(CATALOG, client).statusCode()).isEqualTo(200);
    }

    @Test
    void shouldDeleteOnlyIdleBucketsTest() {
        jdbc.update("INSERT INTO rate_limit_buckets VALUES ('general|ip:old', 1, now() - interval '2 hours')");
        jdbc.update("INSERT INTO rate_limit_buckets VALUES ('general|ip:new', 1, now())");

        limiter.deleteIdleBuckets();

        assertThat(jdbc.queryForList("SELECT bucket_key FROM rate_limit_buckets", String.class))
                .containsExactly("general|ip:new");
    }
}
