package io.github.harishnoone.servicingagent.telemetry;

import io.github.harishnoone.servicingagent.model.AgentTurnReport;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Accumulates {@link AgentTurnReport.ReasoningStep} entries for a single agent turn.
 *
 * <p>Declared {@code @RequestScope} so each HTTP request gets its own instance.
 * {@code latencyMs} on each step is the time spent in that step (FR-7.1: per-tool
 * latency), not the cumulative time since the turn started.
 */
@Component
@RequestScope
public class TraceCollector {

    private final List<AgentTurnReport.ReasoningStep> steps = new ArrayList<>();
    private final long turnStartMs = System.currentTimeMillis();
    private long lastStepMs = turnStartMs;

    /**
     * Records a step whose latency is the time elapsed since the previous step.
     * Use for markers (e.g. {@code PII_SANITIZATION_PASSED}).
     */
    public synchronized void record(String event) {
        long now = System.currentTimeMillis();
        add(event, now - lastStepMs, now);
    }

    /** Records a step with an explicitly measured duration (e.g. a tool call). */
    public synchronized void record(String event, long latencyMs) {
        add(event, latencyMs, System.currentTimeMillis());
    }

    private void add(String event, long latencyMs, long now) {
        steps.add(new AgentTurnReport.ReasoningStep(steps.size() + 1, event, Math.max(0, latencyMs)));
        lastStepMs = now;
    }

    /** Returns an unmodifiable snapshot of all steps recorded so far. */
    public synchronized List<AgentTurnReport.ReasoningStep> getSteps() {
        return Collections.unmodifiableList(new ArrayList<>(steps));
    }

    /** Returns the total elapsed milliseconds from turn start to now. */
    public long getTotalLatencyMs() {
        return System.currentTimeMillis() - turnStartMs;
    }
}
