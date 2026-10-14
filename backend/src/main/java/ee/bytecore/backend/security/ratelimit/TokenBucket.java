package ee.bytecore.backend.security.ratelimit;

/**
 * Pure token-bucket arithmetic. {@link PostgresRateLimiter} performs the same
 * calculation atomically in SQL; this class is the reference used for
 * Retry-After and is unit tested on its own.
 */
public record TokenBucket(double capacity, double refillPerSecond) {

    public TokenBucket {
        if (!(capacity >= 1) || !Double.isFinite(capacity))
            throw new IllegalArgumentException("Bucket capacity must be at least one token");
        if (!(refillPerSecond > 0) || !Double.isFinite(refillPerSecond))
            throw new IllegalArgumentException("Bucket refill rate must be positive");
    }

    public record Decision(boolean allowed, double remainingTokens, long retryAfterSeconds) {}

    /** Tokens available after {@code elapsedSeconds} of refill, capped at capacity; a missing bucket is full. */
    public double refill(Double tokens, double elapsedSeconds) {
        if (tokens == null) return capacity;
        return Math.min(capacity, tokens + Math.max(0, elapsedSeconds) * refillPerSecond);
    }

    public Decision consume(Double tokens, double elapsedSeconds, double cost) {
        if (!(cost > 0) || cost > capacity) throw new IllegalArgumentException("Cost must be within bucket capacity");
        double available = refill(tokens, elapsedSeconds);
        if (available >= cost) return new Decision(true, available - cost, 0);
        return new Decision(false, available, retryAfterSeconds(available, cost));
    }

    /** Whole seconds until {@code cost} tokens are available again (at least one). */
    public long retryAfterSeconds(double available, double cost) {
        return Math.max(1, (long) Math.ceil((cost - available) / refillPerSecond));
    }

    /** Seconds after which an untouched bucket is full again, so its row can be dropped losslessly. */
    public double secondsToFull() {
        return capacity / refillPerSecond;
    }
}
