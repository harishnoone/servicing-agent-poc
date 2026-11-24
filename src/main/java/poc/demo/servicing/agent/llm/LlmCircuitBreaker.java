package poc.demo.servicing.agent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Stops calling the model provider while it is failing (NFR-5). After {@code failureThreshold} consecutive
 * failed model calls (HTTP 429/5xx or an I/O error such as a read timeout) the circuit opens for
 * {@code openSeconds}: turns skip the model and return the deterministic answer straight away, so a provider
 * overload costs about 1.5 s per turn instead of retries and timeouts. When the window ends, the next turn is a
 * trial: a successful call closes the circuit, a failed one opens it again.
 */
@Component
public class LlmCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(LlmCircuitBreaker.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long openMillis;
    private final Clock clock;

    private int consecutiveFailures;
    private long openedAt;
    private State state = State.CLOSED;

    @Autowired
    public LlmCircuitBreaker(@Value("${agent.llm.circuit.failure-threshold:3}") int failureThreshold,
                             @Value("${agent.llm.circuit.open-seconds:60}") long openSeconds) {
        this(failureThreshold, openSeconds, Clock.systemUTC());
    }

    LlmCircuitBreaker(int failureThreshold, long openSeconds, Clock clock) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openMillis = openSeconds * 1000;
        this.clock = clock;
    }

    /** @return true if a turn may call the model now; false means use the deterministic answer */
    public synchronized boolean allowRequest() {
        if (state == State.OPEN && clock.millis() - openedAt >= openMillis) {
            state = State.HALF_OPEN;
            log.info("LLM circuit HALF_OPEN: next model call is a trial");
        }
        return state != State.OPEN;
    }

    public synchronized void recordSuccess() {
        if (state != State.CLOSED) {
            log.info("LLM circuit CLOSED: provider answered again");
        }
        state = State.CLOSED;
        consecutiveFailures = 0;
    }

    public synchronized void recordFailure(String reason) {
        consecutiveFailures++;
        if (state == State.HALF_OPEN || (state == State.CLOSED && consecutiveFailures >= failureThreshold)) {
            state = State.OPEN;
            openedAt = clock.millis();
            log.warn("LLM circuit OPEN for {} s after {} consecutive failures (last: {}); turns use the "
                    + "deterministic answer", openMillis / 1000, consecutiveFailures, reason);
        }
    }

    public synchronized State state() {
        return state;
    }
}
