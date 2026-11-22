package unit.tests.servicingagent.prompt;

import unit.tests.servicingagent.telemetry.KpiMetricsService;
import unit.tests.servicingagent.telemetry.TraceCollector;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

/**
 * Reads token usage from the provider response and records cache hits (FR-3.7).
 *
 * <p>OpenAI reports cached input as {@code prompt_tokens_details.cached_tokens}. Other providers
 * report cache reads and writes differently and are not wired in this POC. A missing value is
 * recorded as zero cached tokens, which is what a cold or disabled cache looks like.
 */
@Component
public class CacheUsageRecorder {

    private final KpiMetricsService kpi;
    private final TraceCollector trace;
    private final StaticPromptPrefix prefix;

    public CacheUsageRecorder(KpiMetricsService kpi, TraceCollector trace, StaticPromptPrefix prefix) {
        this.kpi = kpi;
        this.trace = trace;
        this.prefix = prefix;
    }

    public void record(ChatResponse response) {
        Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage == null || usage.getPromptTokens() == null) {
            trace.record("LLM_USAGE_UNAVAILABLE");
            return;
        }
        long prompt = usage.getPromptTokens();
        long cached = cachedTokens(usage);
        kpi.recordLlmUsage(prompt, cached);
        trace.record("LLM_USAGE (promptTokens=" + prompt + ", cachedTokens=" + cached
                + ", prefix=" + prefix.version() + ")");
    }

    static long cachedTokens(Usage usage) {
        if (usage.getNativeUsage() instanceof OpenAiApi.Usage u
                && u.promptTokensDetails() != null && u.promptTokensDetails().cachedTokens() != null) {
            return u.promptTokensDetails().cachedTokens();
        }
        return 0L;
    }
}