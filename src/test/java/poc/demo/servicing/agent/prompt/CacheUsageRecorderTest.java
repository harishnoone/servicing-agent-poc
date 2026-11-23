package poc.demo.servicing.agent.prompt;

import poc.demo.servicing.agent.telemetry.KpiMetricsService;
import poc.demo.servicing.agent.telemetry.TraceCollector;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-3.7: cache read tokens from provider usage reach the KPI report. */
class CacheUsageRecorderTest {

    private final KpiMetricsService kpi = new KpiMetricsService();
    private final TraceCollector trace = new TraceCollector();
    private final CacheUsageRecorder recorder = new CacheUsageRecorder(kpi, trace,
            new StaticPromptPrefix("sys", false, "classpath:prompts/policy-digest.md", "classpath:prompts/few-shot-examples.md"));

    private static ChatResponse response(int prompt, Integer cached) {
        OpenAiApi.Usage.PromptTokensDetails details = cached == null ? null : new OpenAiApi.Usage.PromptTokensDetails(0, cached);
        OpenAiApi.Usage nativeUsage = new OpenAiApi.Usage(20, prompt, prompt + 20, details, null);
        ChatResponseMetadata meta = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(prompt, 20, prompt + 20, nativeUsage)).build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))), meta);
    }

    @Test
    void firstTurnWritesAndLaterTurnReadsCachedPrefix() {
        recorder.record(response(1200, 0));      // turn 1: cold
        recorder.record(response(1300, 1024));   // turn 2: prefix served from cache
        KpiMetricsService.KpiReport r = kpi.getReport();
        assertThat(r.llmCalls()).isEqualTo(2);
        assertThat(r.promptTokens()).isEqualTo(2500);
        assertThat(r.cachedPromptTokens()).isEqualTo(1024);
        assertThat(r.promptCacheHitRatePercent()).isEqualTo(40.96);
    }

    @Test
    void missingCacheDetailsCountAsZeroCached() {
        recorder.record(response(900, null));
        assertThat(kpi.getReport().cachedPromptTokens()).isZero();
        assertThat(kpi.getReport().promptTokens()).isEqualTo(900);
    }

    @Test
    void missingUsageIsTracedAndNotCounted() {
        recorder.record(null);
        assertThat(kpi.getReport().llmCalls()).isZero();
        assertThat(trace.getSteps()).anyMatch(s -> s.event().equals("LLM_USAGE_UNAVAILABLE"));
    }

    @Test
    void cachedTokensAreCappedAtPromptTokens() {
        kpi.recordLlmUsage(100, 500);
        assertThat(kpi.getReport().cachedPromptTokens()).isEqualTo(100);
    }
}