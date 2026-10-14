package ee.bytecore.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.ResourceAccessException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class HydraTokenIntrospectorTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

    private final List<String> calls = new ArrayList<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    private HydraTokenIntrospector introspector(Function<String, HydraTokenIntrospector.Introspection> hydra) {
        return introspector(hydra, 10_000);
    }

    private HydraTokenIntrospector introspector(
            Function<String, HydraTokenIntrospector.Introspection> hydra, int maxEntries) {
        return new HydraTokenIntrospector(
                token -> {
                    calls.add(token);
                    return hydra.apply(token);
                },
                Duration.ofSeconds(10),
                maxEntries,
                clock);
    }

    private static Jwt jwt(String token, String subject) {
        return Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(NOW.minusSeconds(60))
                .expiresAt(NOW.plusSeconds(1800))
                .build();
    }

    @Test
    void shouldAcceptActiveTokenWithMatchingSubjectTest() {
        var validator = introspector(token -> new HydraTokenIntrospector.Introspection(true, "42"));

        assertThat(validator.validate(jwt("a", "42")).hasErrors()).isFalse();
    }

    @Test
    void shouldRejectInactiveTokenTest() {
        var validator = introspector(token -> new HydraTokenIntrospector.Introspection(false, null));

        var result = validator.validate(jwt("a", "42"));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).allMatch(error -> OAuth2ErrorCodes.INVALID_TOKEN.equals(error.getErrorCode()));
    }

    @Test
    void shouldRejectActiveTokenForDifferentSubjectTest() {
        var validator = introspector(token -> new HydraTokenIntrospector.Introspection(true, "43"));

        assertThat(validator.validate(jwt("a", "42")).hasErrors()).isTrue();
    }

    @Test
    void shouldFailClosedAndNotCacheWhenIntrospectionFailsTest() {
        var validator = introspector(token -> {
            throw new ResourceAccessException("timeout");
        });

        assertThat(validator.validate(jwt("a", "42")).hasErrors()).isTrue();
        assertThat(validator.validate(jwt("a", "42")).hasErrors()).isTrue();
        assertThat(calls).hasSize(2);
        assertThat(validator.cacheSize()).isZero();
    }

    @Test
    void shouldServeRepeatedChecksFromCacheWithinTtlTest() {
        var validator = introspector(token -> new HydraTokenIntrospector.Introspection(true, "42"));

        validator.validate(jwt("a", "42"));
        now.set(NOW.plusSeconds(9));
        validator.validate(jwt("a", "42"));

        assertThat(calls).hasSize(1);
    }

    @Test
    void shouldReintrospectAfterTtlSoRevocationTakesEffectTest() {
        var active = new AtomicReference<>(true);
        var validator = introspector(token -> new HydraTokenIntrospector.Introspection(active.get(), "42"));

        assertThat(validator.validate(jwt("a", "42")).hasErrors()).isFalse();
        active.set(false);
        now.set(NOW.plusSeconds(10));

        assertThat(validator.validate(jwt("a", "42")).hasErrors()).isTrue();
        assertThat(calls).hasSize(2);
    }

    @Test
    void shouldNotCacheBeyondTokenExpiryTest() {
        var validator = introspector(token -> new HydraTokenIntrospector.Introspection(true, "42"));
        var shortLived = Jwt.withTokenValue("a")
                .header("alg", "RS256")
                .subject("42")
                .expiresAt(NOW.plusSeconds(2))
                .build();

        validator.validate(shortLived);
        now.set(NOW.plusSeconds(2));
        validator.validate(shortLived);

        assertThat(calls).hasSize(2);
    }

    @Test
    void shouldBoundCacheSizeByEvictingLeastRecentlyUsedTest() {
        var validator = introspector(token -> new HydraTokenIntrospector.Introspection(true, "42"), 3);

        for (String token : List.of("a", "b", "c", "d", "e")) {
            validator.validate(jwt(token, "42"));
        }

        assertThat(validator.cacheSize()).isEqualTo(3);
        validator.validate(jwt("e", "42"));
        assertThat(calls).hasSize(5);
        validator.validate(jwt("a", "42"));
        assertThat(calls).hasSize(6);
    }

    @Test
    void shouldNeverStoreRawTokensInCacheKeysTest() throws Exception {
        var validator = introspector(token -> new HydraTokenIntrospector.Introspection(true, "42"));
        validator.validate(jwt("secret-token-value", "42"));

        var field = HydraTokenIntrospector.class.getDeclaredField("cache");
        field.setAccessible(true);
        var cache = (java.util.Map<?, ?>) field.get(validator);
        assertThat(cache.keySet()).noneMatch(key -> key.toString().contains("secret-token-value"));
    }

    @Test
    void shouldRejectCacheTtlAboveThirtySecondsTest() {
        assertThatThrownBy(() -> new HydraTokenIntrospector(
                        token -> new HydraTokenIntrospector.Introspection(true, "42"),
                        Duration.ofSeconds(31),
                        10,
                        clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
