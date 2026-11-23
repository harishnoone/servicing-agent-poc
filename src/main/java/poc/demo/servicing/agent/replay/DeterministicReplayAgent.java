package poc.demo.servicing.agent.replay;

import poc.demo.servicing.agent.model.CustomerProfile;
import poc.demo.servicing.agent.model.WaiverEvaluationResult;
import poc.demo.servicing.agent.telemetry.TurnState;
import poc.demo.servicing.agent.tools.CustomerContextTools;
import poc.demo.servicing.agent.tools.DeterministicRulesTools;
import poc.demo.servicing.agent.tools.EscalationTools;
import poc.demo.servicing.agent.tools.KnowledgeRetrievalTools;
import poc.demo.servicing.agent.tools.KnowledgeRetrievalTools.SearchResponse;
import poc.demo.servicing.agent.tools.KnowledgeRetrievalTools.SearchResult;
import poc.demo.servicing.agent.tools.TicketMatchTools;
import poc.demo.servicing.agent.tools.TicketMatchTools.TicketMatch;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.math.RoundingMode;

/**
 * Offline stand-in for the LLM planner (NFR-1: demo must run without cloud model access).
 *
 * <p>It runs the same tools the LLM would, in a fixed plan chosen by keyword intent, and
 * writes a templated answer. Facts and decisions still come only from the tools, so traces,
 * citations, waiver outcomes and escalations behave the same as in LLM mode. Selected with
 * {@code agent.llm.mode=replay}, or automatically when no model API key is configured.
 */
@Component
public class DeterministicReplayAgent {

    private static final Pattern DOLLARS = Pattern.compile("\\$\\s?(\\d+(?:\\.\\d{1,2})?)");
    private static final List<String> TECH_WORDS =
            List.of("err-", "gateway", "504", "outage", "timeout", "certificate", "incident", "api is down");
    private static final List<String> POLICY_WORDS =
            List.of("policy", "credit", "sla", "eligib", "refund", "allowed", "rule", "entitle");
    private static final List<String> BILLING_WORDS =
            List.of("waive", "waiver", "late fee", "late-fee", "fee");

    private final CustomerContextTools customerTools;
    private final KnowledgeRetrievalTools knowledgeTools;
    private final DeterministicRulesTools rulesTools;
    private final EscalationTools escalationTools;
    private final TicketMatchTools ticketMatchTools;
    private final TurnState turnState;

    public DeterministicReplayAgent(CustomerContextTools customerTools, KnowledgeRetrievalTools knowledgeTools,
                                    DeterministicRulesTools rulesTools, EscalationTools escalationTools,
                                    TicketMatchTools ticketMatchTools, TurnState turnState) {
        this.customerTools = customerTools;
        this.knowledgeTools = knowledgeTools;
        this.rulesTools = rulesTools;
        this.escalationTools = escalationTools;
        this.ticketMatchTools = ticketMatchTools;
        this.turnState = turnState;
    }

    public String respond(String customerId, String message) {
        CustomerProfile profile = customerTools.getCustomerState(customerId);
        String lower = message.toLowerCase();
        boolean policyQuestion = POLICY_WORDS.stream().anyMatch(lower::contains) && !lower.contains("err-");
        boolean technical = !policyQuestion && TECH_WORDS.stream().anyMatch(lower::contains);
        boolean billing = BILLING_WORDS.stream().anyMatch(lower::contains);

        if (technical) {
            return technical(profile, message);
        }
        if (billing && !policyQuestion) {
            return waiver(profile, message);
        }
        return general(profile, message);
    }

    // -------------------------------------------------------------------------

    private String waiver(CustomerProfile profile, String message) {
        List<String> steps = new ArrayList<>(List.of("Loaded customer profile"));
        SearchResponse policy = knowledgeTools.searchPoliciesAndCases("late fee waiver policy eligibility");
        steps.add("Searched waiver policy");

        BigDecimal amount = requestedAmount(profile, message);
        if (amount == null) {
            return "I can check waiver eligibility, but I could not find a late fee on your account or in your "
                    + "message. Which fee would you like reviewed, and for how much?";
        }
        WaiverEvaluationResult eval = rulesTools.evaluateWaiverRules(profile.customerId(), amount);
        steps.add("Evaluated waiver rules: " + eval.reasonCode());

        boolean needsSupervisor = "TENURE_TOO_SHORT".equals(eval.reasonCode());
        EscalationTools.EscalationDecision esc = escalationTools.escalateWithSummary(
                profile.customerId(), message, "BILLING_WAIVER", steps, needsSupervisor);

        String cite = "[" + eval.policyCitation() + "]";
        if (esc.escalated()) {
            return "I reviewed your late fee of $" + amount + " against " + cite + ". " + eval.explanation()
                    + " I have passed your case to a Tier-2 specialist with the full context, so you will not "
                    + "need to repeat yourself.";
        }
        if (eval.eligible()) {
            return "I reviewed your account and the $" + amount + " late fee. You are a " + profile.accountTier()
                    + " customer with " + profile.tenureMonths() + " months of tenure and no waivers used in the "
                    + "past 12 months, so you qualify for a one-time waiver under policy " + cite + ". "
                    + "The approval has been recorded for audit.";
        }
        return "I reviewed the $" + amount + " late fee against " + cite + ". I cannot waive it: "
                + eval.explanation() + " If you would like, I can ask a supervisor to review it.";
    }

    private String technical(CustomerProfile profile, String message) {
        List<String> steps = new ArrayList<>(List.of("Loaded customer profile"));
        TicketMatch existing = checkExistingTicket(profile, message, steps);
        SearchResponse found = knowledgeTools.searchPoliciesAndCases(message);
        steps.add("Searched troubleshooting knowledge (confidence " + found.retrievalConfidence() + ")");

        EscalationTools.EscalationDecision esc = escalationTools.escalateWithSummary(
                profile.customerId(), message, "TECHNICAL_OUTAGE", steps, false);

        StringBuilder out = new StringBuilder(existingTicketSentence(existing));
        SearchResult canonical = found.results().stream()
                .filter(r -> !r.superseded() && "CANONICAL_POLICY".equals(r.authorityLevel())).findFirst().orElse(null);
        if (!found.lowConfidence() && canonical != null) {
            out.append("The current guidance is in [").append(canonical.documentId()).append("]: ")
                    .append(firstLines(canonical.content(), 2, false)).append(" ");
        }
        for (SearchResult r : found.results()) {
            if (r.superseded()) {
                out.append("An older case [").append(r.documentId()).append("] suggested a different fix, but it "
                        + "has been superseded by ").append(r.supersededBy())
                        .append(" and must not be followed. ");
            }
        }
        if (found.lowConfidence()) {
            out.append("I could not find a confident match for this in current policy. ");
        }
        if (esc.escalated()) {
            out.append(escalationSentence(esc));
        }
        return out.toString().strip();
    }

    private String general(CustomerProfile profile, String message) {
        List<String> steps = new ArrayList<>(List.of("Loaded customer profile"));
        TicketMatch existing = checkExistingTicket(profile, message, steps);
        String lower = message.toLowerCase();
        // Account-status questions are answered from the profile alone; only policy questions need retrieval.
        SearchResponse found = null;
        if (POLICY_WORDS.stream().anyMatch(lower::contains)) {
            found = knowledgeTools.searchPoliciesAndCases(message);
            steps.add("Searched policies (confidence " + found.retrievalConfidence() + ")");
        }
        EscalationTools.EscalationDecision esc = escalationTools.escalateWithSummary(
                profile.customerId(), message, found == null ? "ACCOUNT_STATUS" : "POLICY_QUESTION", steps, false);

        StringBuilder out = new StringBuilder("Here is what I see on your account: ")
                .append(profile.accountTier()).append(" tier, ").append(profile.tenureMonths()).append(" months, ");
        if (profile.recentCharges().isEmpty()) {
            out.append("no recent charges. ");
        } else {
            CustomerProfile.LedgerEntry c = profile.recentCharges().get(0);
            out.append("latest charge ").append(c.chargeType()).append(" of $").append(c.amount())
                    .append(" (").append(c.status()).append("). ");
        }
        if (existing.matched()) {
            out.insert(0, existingTicketSentence(existing));
        } else if (!profile.activeTickets().isEmpty()) {
            CustomerProfile.TicketSummary t = profile.activeTickets().get(0);
            out.append("Ticket ").append(t.ticketId()).append(" is ").append(t.status()).append(". ");
        }
        if (found != null) {
            SearchResponse f = found;
            if (f.lowConfidence()) {
                out.append("I could not find a confident match for this in current policy. ");
            } else {
                f.results().stream().filter(r -> !r.superseded()).findFirst().ifPresent(r -> out.insert(0,
                        "Per [" + r.documentId() + "]: " + firstLines(r.content(), 3, true) + " "));
            }
        }
        if (esc.escalated()) {
            out.append(escalationSentence(esc));
        }
        return out.toString().strip();
    }
    // -------------------------------------------------------------------------

    /** FR-6.3: runs the existing-ticket check and records it as a verified step for the handoff card. */
    private TicketMatch checkExistingTicket(CustomerProfile profile, String message, List<String> steps) {
        TicketMatch m = ticketMatchTools.findMatchingOpenTicket(profile.customerId(), message);
        if (m.matched()) {
            steps.add("Existing open ticket " + m.ticketId() + " (" + m.status() + ") already covers this request ("
                    + m.matchReason() + "): treat as a follow-up, do not open a new ticket");
        }
        return m;
    }

    private static String existingTicketSentence(TicketMatch m) {
        if (!m.matched()) {
            return "";
        }
        return "You already have an open ticket for this: " + m.ticketId() + " (\"" + m.subject() + "\", status "
                + m.status() + "). I am treating your message as a follow-up on that ticket, so there is no need "
                + "to open a new one. ";
    }

    /** Customer-facing sentence that matches what actually triggered the escalation. */
    private static String escalationSentence(EscalationTools.EscalationDecision esc) {
        String r = esc.reason();
        if (r.contains("SLA_BREACH")) {
            return "Your ticket is past its SLA, so I have escalated it to a Tier-2 engineer with the account "
                    + "snapshot, steps taken and policy references attached.";
        }
        if (r.contains("HIGH_FRUSTRATION")) {
            return "I am sorry for the frustration. I have escalated this to a Tier-2 specialist with the full context.";
        }
        if (r.contains("LOW_RETRIEVAL_CONFIDENCE")) {
            return "I have passed your question to a Tier-2 specialist who will answer from current policy.";
        }
        return "I have passed your case to a Tier-2 specialist with the full context.";
    }

    /** Dollar amount in the message, else the newest unpaid late fee on the ledger. */
    BigDecimal requestedAmount(CustomerProfile profile, String message) {
        Matcher m = DOLLARS.matcher(message);
        if (m.find()) {
            return new BigDecimal(m.group(1)).setScale(2, RoundingMode.HALF_UP);
        }
        return profile.recentCharges().stream()
                .filter(c -> "LATE_FEE".equalsIgnoreCase(c.chargeType()) && "UNPAID".equalsIgnoreCase(c.status()))
                .map(CustomerProfile.LedgerEntry::amount).findFirst().orElse(null);
    }

    /** First n content lines after the heading; bullets are included (without the dash) when requested. */
    private String firstLines(String text, int n, boolean includeBullets) {
        String[] lines = text.split("\\R");
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = 1; i < lines.length && count < n; i++) {
            String l = lines[i].strip();
            if (l.isBlank() || l.startsWith("**Symptoms")) continue;
            if (l.startsWith("-")) {
                if (!includeBullets) continue;
                l = l.substring(1).strip();
            }
            sb.append(l.replace("**", "")).append(' ');
            count++;
        }
        return sb.toString().strip();
    }
}
