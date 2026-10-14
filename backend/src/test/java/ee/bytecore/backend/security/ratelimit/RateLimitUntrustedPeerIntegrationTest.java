package ee.bytecore.backend.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.integration.payment.PaymentOutboxPublisher;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Loopback is not a trusted proxy here, so a client-supplied X-Forwarded-For must be ignored. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.kafka.bootstrap-servers=127.0.0.1:1",
            "spring.kafka.listener.auto-startup=false",
            "spring.docker.compose.enabled=false",
            "rate-limit.enabled=true",
            "rate-limit.trusted-proxies=192.0.2.0/24",
            "rate-limit.policies.general.capacity=2",
            "rate-limit.policies.general.refill-tokens=1",
            "rate-limit.policies.general.refill-period=PT1M"
        })
@Import(PostgresTestConfiguration.class)
@Tag("integration")
@Timeout(60)
class RateLimitUntrustedPeerIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PaymentOutboxPublisher outboxPublisher;

    @Test
    void shouldIgnoreSpoofedForwardedForFromUntrustedPeerTest() throws Exception {
        jdbc.update("DELETE FROM rate_limit_buckets");
        HttpClient client = HttpClient.newHttpClient();
        int[] statuses = new int[3];
        for (int i = 0; i < statuses.length; i++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                    .header("Content-Type", "application/json")
                    .header("X-Forwarded-For", "203.0.113." + i)
                    .POST(HttpRequest.BodyPublishers.ofString(RateLimitIntegrationTest.CATALOG))
                    .build();
            statuses[i] =
                    client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
        }

        assertThat(statuses).containsExactly(200, 200, 429);
        assertThat(jdbc.queryForList("SELECT bucket_key FROM rate_limit_buckets", String.class))
                .containsExactly("general|ip:127.0.0.1");
    }
}
