package io.github.harishnoone.servicingagent.llm;

import io.github.harishnoone.servicingagent.model.CustomerProfile;
import io.github.harishnoone.servicingagent.prompt.CacheUsageRecorder;
import io.github.harishnoone.servicingagent.telemetry.TraceCollector;
import io.github.harishnoone.servicingagent.telemetry.TurnState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Single-pass LLM mode: Java has already run the tool plan, so the model is called exactly once,
 * with no tools, to turn the verified facts and the deterministic draft into the customer reply.
 * Decisions, citations and escalation are fixed before this call. The call has a hard timeout; on
 * timeout or error the caller keeps the deterministic draft, so a slow provider never fails a turn.
 */
@Component
public class SingleCallSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(SingleCallSynthesizer.class);
    private static final int MAX_SNIPPET_CHARS = 700;

    private final ChatClient client;
    private final TurnState turnState;
    private final TraceCollector trace;
    private final CacheUsageRecorder cacheUsage;
    private final long timeoutMs;
    private final int maxTokens;
    private final String reasoningEffort;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SingleCallSynthesizer(
            @Qualifier("synthesisChatClient") ChatClient client,
            TurnState turnState,
            TraceCollector trace,
            CacheUsageRecorder cacheUsage,
            @Value("${agent.llm.synthesis.timeout-ms:8000}") long timeoutMs,
            @Value("${agent.llm.synthesis.max-tokens:1024}") int maxTokens,
            @Value("${agent.llm.synthesis.reasoning-effort:}") String reasoningEffort) {
        this.client = client;
        this.turnState = turnState;
        this.trace = trace;
        this.cacheUsage = cacheUsage;
        this.timeoutMs = timeoutMs;
        this.maxTokens = maxTokens;
        this.reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.strip();
    }

    /** @return the model's reply, or {@code null} if it failed or exceeded the timeout */
    public String rewrite(CustomerProfile profile, String sessionId, String message, String draft) {
        String facts = facts(profile, sessionId, message, draft);
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        CompletableFuture<String> call = CompletableFuture.supplyAsync(() -> {
            RequestContextHolder.setRequestAttributes(attrs);
            if (mdc != null) MDC.setContextMap(mdc);
            try {
                OpenAiChatOptions.Builder opts = OpenAiChatOptions.builder().maxTokens(maxTokens);
                if (!reasoningEffort.isEmpty()) opts.reasoningEffort(reasoningEffort);
                ChatResponse r = client.prompt()
                        .options(opts.build())
                        .user(u -> u.text("{input}").param("input", facts))
                        .call()
                        .chatResponse();
                cacheUsage.record(r);
                if (r == null || r.getResult() == null) return null;
                String finish = r.getResult().getMetadata() == null ? null : r.getResult().getMetadata().getFinishReason();
                if (finish != null && finish.equalsIgnoreCase("length")) {
                    // Thinking models count reasoning tokens against max_tokens; a cut-off reply is not usable.
                    trace.record("LLM_SYNTHESIS_TRUNCATED (max-tokens " + maxTokens + ")");
                    log.warn("Single-pass reply hit max-tokens={} (finish_reason=length); using the deterministic draft", maxTokens);
                    return null;
                }
                return r.getResult().getOutput().getText();
            } finally {
                RequestContextHolder.resetRequestAttributes();
                MDC.clear();
            }
        }, executor);
        try {
            String text = call.get(timeoutMs, TimeUnit.MILLISECONDS);
            return text == null || text.isBlank() ? null : text.strip();
        } catch (TimeoutException e) {
            call.cancel(true);
            trace.record("LLM_SYNTHESIS_TIMEOUT (>" + timeoutMs + " ms)");
            log.warn("Single-pass synthesis exceeded {} ms; using the deterministic draft", timeoutMs);
            return null;
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            trace.record("LLM_SYNTHESIS_FAILED: " + cause.getClass().getSimpleName());
            log.warn("Single-pass synthesis failed; using the deterministic draft: {}", cause.toString());
            return null;
        }
    }

    /** Dynamic suffix: everything customer- and turn-specific goes here, never in the system prompt. */
    String facts(CustomerProfile p, String sessionId, String message, String draft) {
        StringBuilder sb = new StringBuilder();
        sb.append("Session: ").append(sessionId).append('\n');
        sb.append("Customer message: ").append(message).append("\n\n");
        if (p != null) {
            sb.append("Verified customer profile: ").append(p.fullName()).append(", ").append(p.accountTier())
                    .append(" tier, ").append(p.tenureMonths()).append(" months tenure, MRR $").append(p.mrr())
                    .append(", waivers used in last 12 months: ").append(p.waiversUsed12m()).append('\n');
            p.recentCharges().stream().limit(5).forEach(c -> sb.append("- charge ").append(c.chargeType())
                    .append(" $").append(c.amount()).append(" ").append(c.status()).append('\n'));
            p.activeTickets().forEach(t -> sb.append("- ticket ").append(t.ticketId()).append(" ")
                    .append(t.status()).append(": ").append(t.subject()).append('\n'));
        }
        sb.append("\nTools executed (in order): ").append(turnState.getToolsExecuted()).append('\n');
        if (turnState.getLastWaiverReasonCode() != null) {
            sb.append("Waiver rule engine result: ").append(turnState.getLastWaiverReasonCode()).append('\n');
        }
        sb.append("Escalated to Tier-2: ").append(turnState.isEscalated()).append('\n');
        var existing = turnState.getTicketMatch();
        if (existing != null && existing.matched()) {
            sb.append("Existing open ticket already covers this request: ").append(existing.ticketId()).append(" (")
                    .append(existing.status()).append("). Say it is a follow-up on that ticket; do not suggest a new ticket.\n");
        }
        var snippets = turnState.getSnippets();
        if (!snippets.isEmpty()) {
            sb.append("\nRetrieved sources (cite only these ids):\n");
            for (TurnState.RetrievedSnippet s : snippets) {
                String content = s.content().length() > MAX_SNIPPET_CHARS
                        ? s.content().substring(0, MAX_SNIPPET_CHARS) + "..." : s.content();
                sb.append("[").append(s.documentId()).append("] ").append(s.authorityLevel())
                        .append(s.superseded() ? " SUPERSEDED by " + s.supersededBy() + " (do not follow)" : "")
                        .append('\n').append(content.strip()).append("\n\n");
            }
        }
        sb.append("Deterministic draft reply (correct facts, decisions and citations):\n").append(draft);
        return sb.toString();
    }
}