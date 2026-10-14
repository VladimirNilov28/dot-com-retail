package ee.bytecore.backend.security.ratelimit;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;

@ConfigurationProperties("rate-limit")
@Getter
@Setter
public class RateLimitProperties {
    /** Applies to every rate-limited request keyed by a client (JWT subject or client IP). */
    public static final String GENERAL = "general";
    /** Replaces {@link #GENERAL} for traffic from a trusted proxy that carries no client address. */
    public static final String PROXY = "proxy";

    /** Only test configuration may switch this off; see docs/security/rate-limiting.md. */
    private boolean enabled = true;

    private String clientIpHeader = "X-Forwarded-For";
    private List<String> trustedProxies = List.of("127.0.0.1/32", "::1/128");
    private Duration cleanupIdleAfter = Duration.ofHours(1);
    private Duration cleanupInterval = Duration.ofMinutes(10);
    private Map<String, Policy> policies = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class Policy {
        private double capacity;
        private double refillTokens;
        private Duration refillPeriod = Duration.ofSeconds(1);
        /** GraphQL root fields that additionally consume from this policy's bucket. */
        private List<String> operations = List.of();

        public TokenBucket bucket() {
            return new TokenBucket(capacity, refillTokens / (refillPeriod.toNanos() / 1e9));
        }
    }

    @PostConstruct
    void validate() {
        if (!enabled) return;
        if (!policies.containsKey(GENERAL) || !policies.containsKey(PROXY))
            throw new IllegalArgumentException("rate-limit.policies must define 'general' and 'proxy'");
        if (clientIpHeader == null || !clientIpHeader.matches("[A-Za-z0-9-]{1,64}"))
            throw new IllegalArgumentException("rate-limit.client-ip-header must be a header name");
        trustedProxies.forEach(Cidr::parse);
        if (cleanupInterval.isNegative() || cleanupInterval.isZero())
            throw new IllegalArgumentException("rate-limit.cleanup-interval must be positive");
        policies.forEach((name, policy) -> {
            if (!name.matches("[a-z][a-z0-9-]{0,29}"))
                throw new IllegalArgumentException("Invalid rate-limit policy name " + name);
            if (policy.getRefillPeriod().isNegative()
                    || policy.getRefillPeriod().isZero())
                throw new IllegalArgumentException("rate-limit." + name + ".refill-period must be positive");
            TokenBucket bucket = policy.bucket();
            if (cleanupIdleAfter.toSeconds() < Math.ceil(bucket.secondsToFull()))
                throw new IllegalArgumentException("rate-limit.cleanup-idle-after must not delete refilling buckets ("
                        + name + " needs " + (long) Math.ceil(bucket.secondsToFull()) + "s)");
        });
    }
}
