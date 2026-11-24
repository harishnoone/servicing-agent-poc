package poc.demo.servicing.agent.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import poc.demo.servicing.agent.prompt.StaticPromptPrefix;

/**
 * Per-turn LLM cost estimate and projected prompt-caching saving (FR-3.7), logged as {@code Turn cost: ...}.
 *
 * <p>Tokens are summed over every model call in the turn. Cost uses the per-million-token prices in
 * {@code agent.llm.pricing.*} (set them to your contract prices; the defaults are placeholders). The projected
 * saving assumes the static prompt prefix is billed at the cached rate on every call. Gemini's OpenAI-compatible
 * endpoint reports no cached tokens, so this is a ceiling, not a billed figure; when a provider reports
 * {@code cached_tokens}, the measured cached count is logged too.
 */
@Component
public class TurnCostEstimator {

    private static final Logger log = LoggerFactory.getLogger(TurnCostEstimator.class);

    private final StaticPromptPrefix prefix;
    private final double inputPerMillion;
    private final double outputPerMillion;
    private final double cachedInputPerMillion;

    public TurnCostEstimator(StaticPromptPrefix prefix,
                             @Value("${agent.llm.pricing.input-per-million-usd:0.10}") double inputPerMillion,
                             @Value("${agent.llm.pricing.output-per-million-usd:0.40}") double outputPerMillion,
                             @Value("${agent.llm.pricing.cached-input-per-million-usd:0.025}") double cachedInputPerMillion) {
        this.prefix = prefix;
        this.inputPerMillion = inputPerMillion;
        this.outputPerMillion = outputPerMillion;
        this.cachedInputPerMillion = cachedInputPerMillion;
    }

    /** Logs one cost line for the turn; does nothing when no model was called. */
    public void logTurn(TurnState state) {
        int calls = state.getLlmRoundTrips();
        if (calls == 0) {
            return;
        }
        long prompt = state.getPromptTokens();
        long completion = state.getCompletionTokens();
        long cacheable = Math.min(prompt, (long) prefix.estimatedTokens() * calls);
        double cost = (prompt * inputPerMillion + completion * outputPerMillion) / 1_000_000;
        double cachedCost = ((prompt - cacheable) * inputPerMillion + cacheable * cachedInputPerMillion
                + completion * outputPerMillion) / 1_000_000;
        double savingPct = cost == 0 ? 0 : 100 * (cost - cachedCost) / cost;
        log.info(String.format("Turn cost: llmCalls=%d promptTokens=%d completionTokens=%d "
                        + "staticPrefixTokens=%d (%d x %d calls) estCostUsd=%.6f ifPrefixCachedUsd=%.6f "
                        + "projectedSaving=%.0f%% providerReportedCachedTokens=%d",
                calls, prompt, completion, cacheable, prefix.estimatedTokens(), calls, cost, cachedCost,
                savingPct, state.getCachedTokens()));
    }
}
