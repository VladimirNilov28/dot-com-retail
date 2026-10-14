package ee.bytecore.backend.security.ratelimit;

import java.time.Duration;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token buckets stored in PostgreSQL ({@code rate_limit_buckets}, V23) so every
 * backend instance shares them. Each check is one atomic upsert: the row lock
 * taken by ON CONFLICT serialises concurrent requests for the same key, the
 * refill is computed from the stored timestamp, and the WHERE clause leaves
 * the bucket untouched (no row returned) when there are not enough tokens.
 */
public class PostgresRateLimiter {
    private static final Logger LOG = LoggerFactory.getLogger(PostgresRateLimiter.class);

    private static final String CONSUME =
            """
            INSERT INTO rate_limit_buckets AS b (bucket_key, tokens, updated_at)
            VALUES (?, ?, now())
            ON CONFLICT (bucket_key) DO UPDATE
               SET tokens = LEAST(?, b.tokens + GREATEST(0, EXTRACT(EPOCH FROM now() - b.updated_at)) * ?) - ?,
                   updated_at = GREATEST(b.updated_at, now())
             WHERE LEAST(?, b.tokens + GREATEST(0, EXTRACT(EPOCH FROM now() - b.updated_at)) * ?) >= ?
            RETURNING tokens
            """;

    private final JdbcTemplate jdbc;
    private final Duration idleAfter;

    public PostgresRateLimiter(JdbcTemplate jdbc, Duration idleAfter) {
        this.jdbc = jdbc;
        this.idleAfter = idleAfter;
    }

    public TokenBucket.Decision tryConsume(String key, TokenBucket bucket, double cost) {
        if (!(cost > 0) || cost > bucket.capacity())
            throw new IllegalArgumentException("Cost must be within bucket capacity");
        double capacity = bucket.capacity();
        double rate = bucket.refillPerSecond();
        List<Double> remaining = jdbc.queryForList(
                CONSUME, Double.class, key, capacity - cost, capacity, rate, cost, capacity, rate, cost);
        if (!remaining.isEmpty()) return new TokenBucket.Decision(true, remaining.getFirst(), 0);
        // Denied: the bucket was not modified; read it only to tell the client when to retry.
        return jdbc
                .query(
                        "SELECT tokens, EXTRACT(EPOCH FROM now() - updated_at) AS elapsed FROM rate_limit_buckets"
                                + " WHERE bucket_key = ?",
                        (rs, row) -> bucket.consume(rs.getDouble("tokens"), rs.getDouble("elapsed"), cost),
                        key)
                .stream()
                .findFirst()
                .map(decision ->
                        decision.allowed() ? new TokenBucket.Decision(false, decision.remainingTokens(), 1) : decision)
                .orElse(new TokenBucket.Decision(false, 0, 1));
    }

    @Scheduled(
            fixedDelayString = "${rate-limit.cleanup-interval:PT10M}",
            initialDelayString = "${rate-limit.cleanup-interval:PT10M}")
    public void deleteIdleBuckets() {
        int deleted = jdbc.update(
                "DELETE FROM rate_limit_buckets WHERE updated_at < now() - make_interval(secs => ?)",
                (double) idleAfter.toSeconds());
        if (deleted > 0) LOG.debug("Deleted {} idle rate-limit buckets", deleted);
    }
}
