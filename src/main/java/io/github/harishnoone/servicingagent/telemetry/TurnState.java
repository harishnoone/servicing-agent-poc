package io.github.harishnoone.servicingagent.telemetry;

import io.github.harishnoone.servicingagent.model.AgentTurnReport;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-request facts that tools establish during an agent turn and that the
 * controller needs afterwards: whether the turn escalated, the handoff card,
 * which documents were retrieved, and the lowest retrieval confidence seen.
 *
 * <p>Using this instead of parsing the LLM's text means deflection status,
 * citations validation and handoff cards come from deterministic tool results.
 */
@Component
@RequestScope
public class TurnState {

    private boolean escalated;
    private AgentTurnReport.AgentHandoffCard handoffCard;
    private final Set<String> retrievedDocIds = new LinkedHashSet<>();
    private final List<String> toolsExecuted = new ArrayList<>();
    private Double lowestRetrievalConfidence;
    private boolean retrievalPerformed;
    private String lastWaiverReasonCode;

    private final java.util.Map<String, io.github.harishnoone.servicingagent.model.CustomerProfile> profiles = new java.util.HashMap<>();
    private final java.util.Map<String, String> profileSources = new java.util.HashMap<>();

    /** Per-turn profile memo so one turn performs at most one cache-miss fallback per customer. */
    public synchronized io.github.harishnoone.servicingagent.model.CustomerProfile cachedProfile(String customerId) {
        return profiles.get(customerId);
    }

    public synchronized String profileSource(String customerId) {
        return profileSources.getOrDefault(customerId, "REDIS_HIT");
    }

    public synchronized void cacheProfile(String customerId, io.github.harishnoone.servicingagent.model.CustomerProfile p, String source) {
        profiles.put(customerId, p);
        profileSources.put(customerId, source);
    }

    public synchronized void markEscalated(AgentTurnReport.AgentHandoffCard card) {
        this.escalated = true;
        this.handoffCard = card;
    }

    public synchronized boolean isEscalated() { return escalated; }

    public synchronized AgentTurnReport.AgentHandoffCard getHandoffCard() { return handoffCard; }

    public synchronized void recordRetrieval(List<String> docIds, double confidence) {
        retrievalPerformed = true;
        retrievedDocIds.addAll(docIds);
        lowestRetrievalConfidence = lowestRetrievalConfidence == null
                ? confidence : Math.min(lowestRetrievalConfidence, confidence);
    }

    public synchronized boolean isRetrievalPerformed() { return retrievalPerformed; }

    public synchronized Set<String> getRetrievedDocIds() { return new LinkedHashSet<>(retrievedDocIds); }

    /** @return lowest confidence across all retrievals this turn, or {@code null} if none ran */
    public synchronized Double getLowestRetrievalConfidence() { return lowestRetrievalConfidence; }

    public synchronized void recordTool(String toolName) { toolsExecuted.add(toolName); }

    public synchronized List<String> getToolsExecuted() { return new ArrayList<>(toolsExecuted); }

    public synchronized void recordWaiverOutcome(String reasonCode) { this.lastWaiverReasonCode = reasonCode; }

    public synchronized String getLastWaiverReasonCode() { return lastWaiverReasonCode; }

    /** Retrieved chunk as the single-pass synthesis call sees it (FR-4). */
    public record RetrievedSnippet(String documentId, String authorityLevel, boolean superseded,
                                   String supersededBy, String content) {}

    private final List<RetrievedSnippet> snippets = new ArrayList<>();

    public synchronized void recordSnippets(List<RetrievedSnippet> found) {
        for (RetrievedSnippet s : found) {
            if (snippets.stream().noneMatch(x -> x.documentId().equals(s.documentId()))) snippets.add(s);
        }
    }
    public synchronized List<RetrievedSnippet> getSnippets() { return new ArrayList<>(snippets); }

    private int llmRoundTrips;
    private long llmMillis;

    /** One HTTP call to the model provider (a turn with tools makes several). Returns the round-trip number. */
    public synchronized int recordLlmRoundTrip(long millis) {
        llmMillis += millis;
        return ++llmRoundTrips;
    }
    public synchronized int getLlmRoundTrips() { return llmRoundTrips; }
    public synchronized long getLlmMillis() { return llmMillis; }
}
