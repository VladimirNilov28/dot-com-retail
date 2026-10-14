package ee.bytecore.backend.security;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Revocation check for Hydra-issued JWT access tokens. Runs after the local
 * signature/issuer/expiry validation and asks Hydra's admin introspection
 * endpoint whether the token is still {@code active} (Hydra reports a JWT access
 * token inactive once its refresh chain or the subject's consent sessions are
 * revoked). Fails closed: an introspection error, timeout or subject mismatch
 * rejects the token (401).
 *
 * <p>Results are cached by SHA-256 of the token (the raw token is never kept) in a
 * bounded LRU map, for at most {@code cache-ttl} and never beyond the token's own
 * expiry. A revoked token can therefore be accepted for at most {@code cache-ttl}
 * after it was last introspected as active.
 */
@Component
@ConditionalOnProperty(
        prefix = "security.token-introspection",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class HydraTokenIntrospector implements OAuth2TokenValidator<Jwt> {

    private static final Logger log = LoggerFactory.getLogger(HydraTokenIntrospector.class);
    private static final OAuth2Error REVOKED =
            new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "The access token is no longer active", null);
    private static final OAuth2Error UNAVAILABLE =
            new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "The access token could not be verified", null);

    /** Introspection outcome; {@code subject} is only meaningful when {@code active}. */
    public record Introspection(boolean active, String subject) {}

    private record Entry(Introspection result, Instant expiresAt) {}

    private final Function<String, Introspection> introspection;
    private final Duration cacheTtl;
    private final int maxEntries;
    private final Clock clock;
    private final Map<String, Entry> cache;

    @Autowired
    public HydraTokenIntrospector(
            @Value("${hydra.admin-url:http://hydra:4445}") String adminUrl,
            @Value("${security.token-introspection.timeout:PT2S}") Duration timeout,
            @Value("${security.token-introspection.cache-ttl:PT10S}") Duration cacheTtl,
            @Value("${security.token-introspection.cache-max-entries:10000}") int maxEntries) {
        this(hydraIntrospection(adminUrl, timeout), cacheTtl, maxEntries, Clock.systemUTC());
    }

    HydraTokenIntrospector(
            Function<String, Introspection> introspection, Duration cacheTtl, int maxEntries, Clock clock) {
        if (cacheTtl.isNegative() || cacheTtl.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("security.token-introspection.cache-ttl must be between 0 and 30s");
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException("security.token-introspection.cache-max-entries must be positive");
        }
        this.introspection = introspection;
        this.cacheTtl = cacheTtl;
        this.maxEntries = maxEntries;
        this.clock = clock;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > HydraTokenIntrospector.this.maxEntries;
            }
        };
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Instant now = clock.instant();
        String key = fingerprint(jwt.getTokenValue());
        Introspection result = cached(key, now);
        if (result == null) {
            try {
                result = Objects.requireNonNull(introspection.apply(jwt.getTokenValue()));
            } catch (RuntimeException e) {
                log.warn(
                        "Token introspection failed ({}); rejecting the token",
                        e.getClass().getSimpleName());
                return OAuth2TokenValidatorResult.failure(UNAVAILABLE);
            }
            store(key, result, jwt.getExpiresAt(), now);
        }
        boolean valid = result.active() && Objects.equals(result.subject(), jwt.getSubject());
        return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(REVOKED);
    }

    int cacheSize() {
        synchronized (cache) {
            return cache.size();
        }
    }

    private Introspection cached(String key, Instant now) {
        synchronized (cache) {
            Entry entry = cache.get(key);
            if (entry == null) return null;
            if (!now.isBefore(entry.expiresAt())) {
                cache.remove(key);
                return null;
            }
            return entry.result();
        }
    }

    private void store(String key, Introspection result, Instant tokenExpiry, Instant now) {
        Instant expiresAt = now.plus(cacheTtl);
        if (tokenExpiry != null && tokenExpiry.isBefore(expiresAt)) expiresAt = tokenExpiry;
        if (!now.isBefore(expiresAt)) return;
        synchronized (cache) {
            cache.put(key, new Entry(result, expiresAt));
        }
    }

    private static String fingerprint(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Function<String, Introspection> hydraIntrospection(String adminUrl, Duration timeout) {
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        RestClient client = RestClient.builder()
                .baseUrl(adminUrl)
                .requestFactory(requestFactory)
                .build();
        return token -> {
            var form = new LinkedMultiValueMap<String, String>();
            form.add("token", token);
            Map<?, ?> body = client.post()
                    .uri("/admin/oauth2/introspect")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            if (body == null) throw new IllegalStateException("Empty introspection response");
            boolean active = Boolean.TRUE.equals(body.get("active"));
            return new Introspection(active, body.get("sub") instanceof String sub ? sub : null);
        };
    }
}
