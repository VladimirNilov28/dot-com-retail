package ee.bytecore.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import ee.bytecore.backend.security.HydraTokenIntrospector;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class JwtDecoderConfigurationTest {

    private HttpServer server;
    private RSAKey key;
    private String baseUrl;
    private final AtomicInteger keyRequests = new AtomicInteger();
    private final AtomicInteger discoveryRequests = new AtomicInteger();
    private final AtomicInteger introspectionRequests = new AtomicInteger();
    private final AtomicReference<String> introspectionBody = new AtomicReference<>("{\"active\":true,\"sub\":\"42\"}");
    private final AtomicInteger introspectionStatus = new AtomicInteger(200);

    @BeforeEach
    void setUp() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("container-test").generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/jwks", exchange -> {
            keyRequests.incrementAndGet();
            byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/.well-known/openid-configuration", exchange -> {
            discoveryRequests.incrementAndGet();
            byte[] body = ("{\"issuer\":\"" + baseUrl + "\",\"jwks_uri\":\"" + baseUrl + "/jwks\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/admin/oauth2/introspect", exchange -> {
            introspectionRequests.incrementAndGet();
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = (form.startsWith("token=") ? introspectionBody.get() : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(introspectionStatus.get(), body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void shouldFetchInternalKeysLazilyAndValidatePublicIssuerTest() throws Exception {
        String publicIssuer = "http://127.0.0.1:1";
        var decoder = SecurityConfig.buildJwtDecoder(publicIssuer, baseUrl + "/jwks", null);

        assertThat(keyRequests.get()).isZero();
        assertThat(decoder.decode(token(publicIssuer, Instant.now().plusSeconds(60)))
                        .getSubject())
                .isEqualTo("42");
        assertThat(keyRequests.get()).isPositive();
        assertThat(discoveryRequests.get()).isZero();
    }

    @Test
    void shouldRejectWrongIssuerWithInternalKeysTest() throws Exception {
        var decoder = SecurityConfig.buildJwtDecoder("http://127.0.0.1:4444", baseUrl + "/jwks", null);
        String token = token("http://hydra:4444", Instant.now().plusSeconds(60));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void shouldRejectExpiredTokenWithInternalKeysTest() throws Exception {
        var decoder = SecurityConfig.buildJwtDecoder(baseUrl, baseUrl + "/jwks", null);
        String token = token(baseUrl, Instant.now().minusSeconds(120));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void shouldPreserveLazyIssuerDiscoveryWithoutExplicitKeysTest() throws Exception {
        var decoder = SecurityConfig.buildJwtDecoder(baseUrl, "", null);

        assertThat(discoveryRequests.get()).isZero();
        assertThat(decoder.decode(token(baseUrl, Instant.now().plusSeconds(60))).getSubject())
                .isEqualTo("42");
        assertThat(discoveryRequests.get()).isPositive();
        assertThat(keyRequests.get()).isPositive();
    }

    @Test
    void shouldAcceptTokenThatHydraReportsActiveTest() throws Exception {
        var decoder = SecurityConfig.buildJwtDecoder(baseUrl, baseUrl + "/jwks", introspector());

        assertThat(decoder.decode(token(baseUrl, Instant.now().plusSeconds(60))).getSubject())
                .isEqualTo("42");
        assertThat(introspectionRequests.get()).isEqualTo(1);
    }

    @Test
    void shouldRejectRevokedTokenThatHydraReportsInactiveTest() throws Exception {
        introspectionBody.set("{\"active\":false}");
        var decoder = SecurityConfig.buildJwtDecoder(baseUrl, baseUrl + "/jwks", introspector());
        String token = token(baseUrl, Instant.now().plusSeconds(60));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void shouldFailClosedWhenIntrospectionErrorsTest() throws Exception {
        introspectionStatus.set(500);
        var decoder = SecurityConfig.buildJwtDecoder(baseUrl, baseUrl + "/jwks", introspector());
        String token = token(baseUrl, Instant.now().plusSeconds(60));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void shouldNotIntrospectTokensFailingLocalValidationTest() throws Exception {
        var decoder = SecurityConfig.buildJwtDecoder(baseUrl, baseUrl + "/jwks", introspector());
        String token = token(baseUrl, Instant.now().minusSeconds(120));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
        assertThat(introspectionRequests.get()).isZero();
    }

    private HydraTokenIntrospector introspector() {
        return new HydraTokenIntrospector(baseUrl, Duration.ofSeconds(2), Duration.ofSeconds(10), 100);
    }

    private String token(String issuer, Instant expiresAt) throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("42")
                .expirationTime(Date.from(expiresAt))
                .build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
