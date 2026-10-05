package ee.bytecore.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.security.oauth2.jwt.JwtException;

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
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void shouldFetchInternalKeysLazilyAndValidatePublicIssuerTest() throws Exception {
        String publicIssuer = "http://127.0.0.1:1";
        var decoder = new SecurityConfig().jwtDecoder(publicIssuer, baseUrl + "/jwks");

        assertThat(keyRequests.get()).isZero();
        assertThat(decoder.decode(token(publicIssuer, Instant.now().plusSeconds(60)))
                        .getSubject())
                .isEqualTo("42");
        assertThat(keyRequests.get()).isPositive();
        assertThat(discoveryRequests.get()).isZero();
    }

    @Test
    void shouldRejectWrongIssuerWithInternalKeysTest() throws Exception {
        var decoder = new SecurityConfig().jwtDecoder("http://127.0.0.1:4444", baseUrl + "/jwks");
        String token = token("http://hydra:4444", Instant.now().plusSeconds(60));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void shouldRejectExpiredTokenWithInternalKeysTest() throws Exception {
        var decoder = new SecurityConfig().jwtDecoder(baseUrl, baseUrl + "/jwks");
        String token = token(baseUrl, Instant.now().minusSeconds(120));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void shouldPreserveLazyIssuerDiscoveryWithoutExplicitKeysTest() throws Exception {
        var decoder = new SecurityConfig().jwtDecoder(baseUrl, "");

        assertThat(discoveryRequests.get()).isZero();
        assertThat(decoder.decode(token(baseUrl, Instant.now().plusSeconds(60))).getSubject())
                .isEqualTo("42");
        assertThat(discoveryRequests.get()).isPositive();
        assertThat(keyRequests.get()).isPositive();
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
