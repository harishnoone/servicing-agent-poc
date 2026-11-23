package poc.demo.servicing.agent.telemetry;

import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe, in-process KPI aggregation (FR-7.2). Counters reset on restart; production would
 * back these with Micrometer meters.
 */
@Service
public class KpiMetricsService {

    private final AtomicLong totalSessions              = new AtomicLong();
    private final AtomicLong autonomousDeflections      = new AtomicLong();
    private final AtomicLong escalations                = new AtomicLong();
    /** Stored as whole milliseconds to avoid floating-point drift. */
    private final AtomicLong totalAhtMillisSaved        = new AtomicLong();
    private final AtomicLong policyComplianceViolations = new AtomicLong();
    private final AtomicLong cacheMissFallbacks         = new AtomicLong();
    private final AtomicLong dltMessageDepth            = new AtomicLong();
    private final AtomicLong llmCalls                   = new AtomicLong();
    private final AtomicLong promptTokens               = new AtomicLong();
    private final AtomicLong cachedPromptTokens         = new AtomicLong();

    public void recordDeflection(double ahtSavedMinutes) {
        autonomousDeflections.incrementAndGet();
        totalAhtMillisSaved.addAndGet(Math.round(ahtSavedMinutes * 60_000.0));
    }

    public void recordEscalation()          { escalations.incrementAndGet(); }
    public void recordSession()             { totalSessions.incrementAndGet(); }
    public void recordComplianceViolation() { policyComplianceViolations.incrementAndGet(); }
    public void recordCacheMiss()           { cacheMissFallbacks.incrementAndGet(); }
    public void setDltDepth(long depth)     { dltMessageDepth.set(depth); }

    /** FR-3.7: one LLM response's input tokens and how many of them the provider served from cache. */
    public void recordLlmUsage(long prompt, long cached) {
        llmCalls.incrementAndGet();
        promptTokens.addAndGet(prompt);
        cachedPromptTokens.addAndGet(Math.min(Math.max(cached, 0), prompt));
    }

    public KpiReport getReport() {
        long sessions    = totalSessions.get();
        long deflections = autonomousDeflections.get();
        long violations  = policyComplianceViolations.get();
        return new KpiReport(
                sessions,
                deflections,
                escalations.get(),
                sessions == 0 ? 0.0 : round(deflections * 100.0 / sessions),
                round(totalAhtMillisSaved.get() / 60_000.0),
                sessions == 0 ? 100.0 : round(Math.max(0, sessions - violations) * 100.0 / sessions),
                cacheMissFallbacks.get(),
                dltMessageDepth.get(),
                llmCalls.get(),
                promptTokens.get(),
                cachedPromptTokens.get(),
                promptTokens.get() == 0 ? 0.0 : round(cachedPromptTokens.get() * 100.0 / promptTokens.get()));
    }

    private static double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    /**
     * @param policyComplianceScore percentage of sessions without a recorded compliance violation
     * @param cacheMissFallbacks    turns that needed the warehouse fallback because Redis had no profile
     * @param dltMessageDepth       unreplayed records across the CDC dead-letter topics
     * @param llmCalls              LLM responses whose usage was recorded (0 in offline replay mode)
     * @param promptTokens          input tokens across those responses
     * @param cachedPromptTokens    input tokens the provider served from its prompt cache
     * @param promptCacheHitRatePercent cachedPromptTokens as a percentage of promptTokens
     */
    public record KpiReport(
            long   totalSessions,
            long   autonomousDeflections,
            long   escalations,
            double deflectionRatePercent,
            double totalAhtMinutesSaved,
            double policyComplianceScore,
            long   cacheMissFallbacks,
            long   dltMessageDepth,
            long   llmCalls,
            long   promptTokens,
            long   cachedPromptTokens,
            double promptCacheHitRatePercent
    ) {}
}
