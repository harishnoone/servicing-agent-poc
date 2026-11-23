package poc.demo.servicing.agent.tools;

import poc.demo.servicing.agent.model.AgentTurnReport.AgentHandoffCard;
import poc.demo.servicing.agent.model.CustomerProfile;
import poc.demo.servicing.agent.telemetry.TraceCollector;
import poc.demo.servicing.agent.telemetry.TurnState;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Decides whether a turn must be handed to a human and compiles the handoff card.
 *
 * <p>FR-6.1: escalation triggers are objective and evaluated here, not by the LLM:
 * high-frustration language, an SLA breach on an unresolved ticket, or retrieval
 * confidence below the configured threshold (as recorded by the search tool this turn).
 * The LLM can additionally force escalation.
 * <p>FR-6.2: the card carries a verified profile snapshot, intent, urgency, the tools
 * actually executed this turn, and cited policy clauses.
 */
@Component
public class EscalationTools {

    static final List<String> HIGH_FRUSTRATION_KEYWORDS = List.of(
            "furious", "unacceptable", "lawsuit", "cancel my", "cancelling", "canceling",
            "ridiculous", "disgusting", "terrible", "horrible", "worst", "fed up", "sick of");

    private static final Set<String> CLOSED_STATUSES = Set.of("RESOLVED", "CLOSED");

    private final CustomerContextTools customerContextTools;
    private final TraceCollector traceCollector;
    private final TurnState turnState;
    private final double minRetrievalConfidence;
    private final Clock clock;

    @Autowired
    public EscalationTools(
            CustomerContextTools customerContextTools,
            TraceCollector traceCollector,
            TurnState turnState,
            @Value("${agent.escalation.min-retrieval-confidence:0.72}") double minRetrievalConfidence) {
        this(customerContextTools, traceCollector, turnState, minRetrievalConfidence, Clock.systemUTC());
    }

    EscalationTools(CustomerContextTools customerContextTools, TraceCollector traceCollector,
                    TurnState turnState, double minRetrievalConfidence, Clock clock) {
        this.customerContextTools   = customerContextTools;
        this.traceCollector         = traceCollector;
        this.turnState              = turnState;
        this.minRetrievalConfidence = minRetrievalConfidence;
        this.clock                  = clock;
    }

    @Tool(description = "Decide whether to hand the conversation to a human Tier-2 agent and compile "
            + "the handoff card. Call this when the customer is highly frustrated, a ticket has "
            + "breached its SLA, retrieval confidence was low, or the request is outside policy. "
            + "The tool re-checks these conditions itself; set forceEscalate=true only when policy "
            + "requires a supervisor (e.g. out-of-policy waiver).")
    public EscalationDecision escalateWithSummary(
            @ToolParam(description = "Customer account ID, e.g. cust_456") String customerId,
            @ToolParam(description = "The customer's latest message, verbatim") String customerMessage,
            @ToolParam(description = "Intent, e.g. TECHNICAL_OUTAGE, BILLING_WAIVER, BILLING_QUESTION") String classifiedIntent,
            @ToolParam(description = "What you already tried or verified this turn, one item per entry") List<String> attemptedSteps,
            @ToolParam(description = "Force escalation regardless of detected conditions") boolean forceEscalate) {

        long start = System.currentTimeMillis();
        CustomerProfile profile = customerContextTools.loadProfile(customerId);

        List<String> triggers = new ArrayList<>();
        String lower = customerMessage == null ? "" : customerMessage.toLowerCase();
        if (HIGH_FRUSTRATION_KEYWORDS.stream().anyMatch(lower::contains)) {
            triggers.add("HIGH_FRUSTRATION");
        }
        List<CustomerProfile.TicketSummary> breached = breachedTickets(profile);
        if (!breached.isEmpty()) {
            triggers.add("SLA_BREACH");
        }
        Double confidence = turnState.getLowestRetrievalConfidence();
        if (confidence != null && confidence < minRetrievalConfidence) {
            triggers.add("LOW_RETRIEVAL_CONFIDENCE");
        }
        if (forceEscalate) {
            triggers.add("AGENT_REQUESTED");
        }

        turnState.recordTool("escalateWithSummary");
        if (triggers.isEmpty()) {
            traceCollector.record("TOOL_CALL: escalateWithSummary -> NO_ESCALATION_NEEDED",
                    System.currentTimeMillis() - start);
            return new EscalationDecision(false, "NO_ESCALATION_NEEDED", null);
        }

        String sentiment = triggers.get(0);
        double urgency = urgency(triggers);

        List<String> attempted = new ArrayList<>(attemptedSteps == null ? List.of() : attemptedSteps);
        attempted.add("Escalation triggers: " + String.join(", ", triggers));
        breached.forEach(t -> attempted.add("Ticket " + t.ticketId() + " (" + t.status()
                + ") passed SLA deadline " + t.slaDeadline()));

        List<String> toolsRun = turnState.getToolsExecuted(); // already includes escalateWithSummary

        AgentHandoffCard card = new AgentHandoffCard(
                profile.customerId(), profile.accountTier(), profile.tenureMonths(), profile.mrr(),
                profile.activeTickets().stream().filter(t -> !CLOSED_STATUSES.contains(t.status())).toList(),
                classifiedIntent == null ? "UNCLASSIFIED" : classifiedIntent,
                sentiment, urgency, toolsRun, attempted,
                List.copyOf(turnState.getRetrievedDocIds()),
                recommendedAction(triggers));

        turnState.markEscalated(card);
        traceCollector.record("TOOL_CALL: escalateWithSummary -> ESCALATED " + triggers,
                System.currentTimeMillis() - start);
        return new EscalationDecision(true, String.join(",", triggers), card);
    }

    private List<CustomerProfile.TicketSummary> breachedTickets(CustomerProfile profile) {
        Instant now = clock.instant();
        return profile.activeTickets().stream()
                .filter(t -> !CLOSED_STATUSES.contains(t.status()))
                .filter(t -> {
                    Instant deadline = parseInstant(t.slaDeadline());
                    return deadline != null && deadline.isBefore(now);
                })
                .toList();
    }

    /**
     * Parses ISO-8601 (Debezium ZonedTimestamp, e.g. {@code 2025-06-01T13:00:00.000000Z}) and
     * Postgres text form ({@code 2025-06-01 13:00:00+00}). Returns {@code null} if absent/unparseable.
     */
    public static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().replace(' ', 'T');
        if (v.matches(".*[+-]\\d{2}$")) {
            v += ":00";
        }
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(v);
            } catch (DateTimeParseException ex) {
                return null;
            }
        }
    }

    private static double urgency(List<String> triggers) {
        double u = 0.5;
        if (triggers.contains("LOW_RETRIEVAL_CONFIDENCE")) u = Math.max(u, 0.6);
        if (triggers.contains("HIGH_FRUSTRATION")) u = Math.max(u, 0.85);
        if (triggers.contains("SLA_BREACH")) u = Math.max(u, 0.95);
        return u;
    }

    private static String recommendedAction(List<String> triggers) {
        if (triggers.contains("SLA_BREACH")) {
            return "SLA breach on an engineering-blocked ticket: page the on-call Engineering Lead and "
                    + "notify the Account Executive per sla_credit_policy.md section 5; evaluate an SLA credit.";
        }
        if (triggers.contains("HIGH_FRUSTRATION")) {
            return "Customer is highly frustrated: acknowledge the experience, review account history, "
                    + "and consider discretionary relief per billing_waivers_policy.md section 4.3.";
        }
        if (triggers.contains("LOW_RETRIEVAL_CONFIDENCE")) {
            return "No policy match above the confidence threshold: answer manually from current policy.";
        }
        return "Out-of-policy or agent-requested escalation: supervisor review per billing_waivers_policy.md section 4.3.";
    }

    /**
     * @param escalated true when the conversation must go to a human
     * @param reason    comma-separated triggers, or {@code NO_ESCALATION_NEEDED}
     * @param handoffCard populated when escalated, otherwise {@code null}
     */
    public record EscalationDecision(boolean escalated, String reason, AgentHandoffCard handoffCard) {}
}
