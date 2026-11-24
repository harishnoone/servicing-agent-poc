package poc.demo.servicing.agent.llm;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LlmCircuitBreakerTest {

    /** Clock the test can move forward. */
    static final class MutableClock extends Clock {
        long millis = 1_000_000;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }

    @Test
    void opensAfterThresholdConsecutiveFailures() {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(3, 60, new MutableClock());
        breaker.recordFailure("HTTP 503");
        breaker.recordFailure("HTTP 503");
        assertThat(breaker.allowRequest()).isTrue();
        breaker.recordFailure("HttpTimeoutException");
        assertThat(breaker.state()).isEqualTo(LlmCircuitBreaker.State.OPEN);
        assertThat(breaker.allowRequest()).isFalse();
    }

    @Test
    void successResetsTheFailureCount() {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(3, 60, new MutableClock());
        breaker.recordFailure("HTTP 503");
        breaker.recordFailure("HTTP 503");
        breaker.recordSuccess();
        breaker.recordFailure("HTTP 503");
        assertThat(breaker.allowRequest()).isTrue();
    }

    @Test
    void halfOpenTrialClosesOnSuccessAndReopensOnFailure() {
        MutableClock clock = new MutableClock();
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(1, 60, clock);
        breaker.recordFailure("HTTP 503");
        assertThat(breaker.allowRequest()).isFalse();

        clock.millis += 60_000;
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.state()).isEqualTo(LlmCircuitBreaker.State.HALF_OPEN);
        breaker.recordFailure("HTTP 503");
        assertThat(breaker.allowRequest()).isFalse();

        clock.millis += 60_000;
        assertThat(breaker.allowRequest()).isTrue();
        breaker.recordSuccess();
        assertThat(breaker.state()).isEqualTo(LlmCircuitBreaker.State.CLOSED);
    }
}
