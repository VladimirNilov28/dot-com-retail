package ee.bytecore.backend.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TokenBucketTest {

    private final TokenBucket bucket = new TokenBucket(5, 2);

    @Test
    void shouldStartFullAndAllowBurstUpToCapacityTest() {
        Double tokens = null;
        for (int i = 0; i < 5; i++) {
            TokenBucket.Decision decision = bucket.consume(tokens, 0, 1);
            assertThat(decision.allowed()).isTrue();
            tokens = decision.remainingTokens();
        }
        assertThat(tokens).isZero();

        TokenBucket.Decision denied = bucket.consume(tokens, 0, 1);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.remainingTokens()).isZero();
        assertThat(denied.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void shouldRefillProportionallyToElapsedTimeCappedAtCapacityTest() {
        assertThat(bucket.refill(0.0, 0.25)).isEqualTo(0.5);
        assertThat(bucket.refill(1.0, 1)).isEqualTo(3.0);
        assertThat(bucket.refill(4.0, 3600)).isEqualTo(5.0);
        assertThat(bucket.refill(2.0, -10)).isEqualTo(2.0);
    }

    @Test
    void shouldDenyUntilEnoughTokensHaveRefilledTest() {
        TokenBucket slow = new TokenBucket(5, 5.0 / 600);

        TokenBucket.Decision denied = slow.consume(0.0, 60, 1);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(60);

        TokenBucket.Decision allowed = slow.consume(0.0, 120, 1);
        assertThat(allowed.allowed()).isTrue();
        assertThat(allowed.remainingTokens()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void shouldKeepBucketsIndependentPerStoredStateTest() {
        Double exhausted = 0.0;
        Double untouched = null;

        assertThat(bucket.consume(exhausted, 0, 1).allowed()).isFalse();
        assertThat(bucket.consume(untouched, 0, 1).allowed()).isTrue();
        assertThat(bucket.consume(untouched, 0, 1).remainingTokens()).isEqualTo(4.0);
    }

    @Test
    void shouldReportTimeUntilFullForIdleCleanupTest() {
        assertThat(bucket.secondsToFull()).isEqualTo(2.5);
        assertThat(new TokenBucket(5, 5.0 / 600).secondsToFull())
                .isCloseTo(600.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void shouldRejectInvalidConfigurationAndCostTest() {
        assertThatThrownBy(() -> new TokenBucket(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucket(5, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucket(Double.NaN, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bucket.consume(null, 0, 6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bucket.consume(null, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
