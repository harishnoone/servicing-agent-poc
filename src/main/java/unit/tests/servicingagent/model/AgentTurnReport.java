package unit.tests.servicingagent.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Comprehensive JSON report returned by POST /api/agent/chat.
 * Every internal step, tool call latency, citation, and KPI impact
 * is visible in Swagger UI; this is the primary demo inspection surface (FR-7.1).
 */
public record AgentTurnReport(
        String sessionId,
        String customerId,
        CustomerSnapshot customerSnapshot,
        List<ReasoningStep> agentReasoningTrace,
        String finalResponse,
        List<String> citations,
        KpiImpact kpiImpact,
        AgentHandoffCard handoffCard
) {
    public record CustomerSnapshot(
            String accountTier,
            int tenureMonths,
            long lastAppliedLsn
    ) {}

    public record ReasoningStep(
            int step,
            String event,
            long latencyMs
    ) {}

    public record KpiImpact(
            String deflectionStatus,       // RESOLVED_AUTONOMOUSLY | ESCALATED | PARTIAL
            double estimatedAhtSavedMinutes,
            long totalTurnLatencyMs
    ) {}

    /**
     * Structured summary for the Tier-2 human agent (FR-6.2).
     *
     * @param customerId            account identifier
     * @param tier                  verified account tier
     * @param tenureMonths          verified tenure
     * @param mrr                   verified monthly recurring revenue
     * @param openTickets           open ticket snapshot from the Redis profile
     * @param classifiedIntent      intent classified by the agent (e.g. TECHNICAL_OUTAGE)
     * @param detectedSentiment     sentiment/urgency label that drove the escalation
     * @param urgencyScore          0.0 to 1.0 urgency score
     * @param toolsExecuted         chronological list of tools run during the turn
     * @param attemptedSteps        facts verified and steps the agent already took
     * @param policyCitations       cited policy clauses
     * @param recommendedNextAction suggested next step for the human agent
     */
    public record AgentHandoffCard(
            String customerId,
            String tier,
            int tenureMonths,
            BigDecimal mrr,
            List<CustomerProfile.TicketSummary> openTickets,
            String classifiedIntent,
            String detectedSentiment,
            double urgencyScore,
            List<String> toolsExecuted,
            List<String> attemptedSteps,
            List<String> policyCitations,
            String recommendedNextAction
    ) {}
}
