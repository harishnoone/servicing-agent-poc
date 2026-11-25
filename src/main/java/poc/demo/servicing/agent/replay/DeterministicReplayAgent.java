package poc.demo.servicing.agent.replay;

import poc.demo.servicing.agent.memory.ConversationMemory.Exchange;
import poc.demo.servicing.agent.model.CustomerProfile;
import poc.demo.servicing.agent.model.WaiverEvaluationResult;
import poc.demo.servicing.agent.telemetry.TraceCollector;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private static final List<String> INVESTIGATION_WORDS = List.of(
            "bill so high", "bill is high", "bill higher", "bill is higher", "bill went up", "bill go up",
            "bill increase", "higher than usual", "explain my bill", "what is this charge", "what are these charges",
            "charged more", "why was i charged");
    private static final Set<String> OUTAGE_CATEGORIES = Set.of("GATEWAY_OUTAGE", "OUTAGE", "INCIDENT");
    private static final Set<String> CLOSED = Set.of("RESOLVED", "CLOSED");

    private final CustomerContextTools customerTools;
    private final KnowledgeRetrievalTools knowledgeTools;
    private final DeterministicRulesTools rulesTools;
    private final EscalationTools escalationTools;
    private final TicketMatchTools ticketMatchTools;
    private final TurnState turnState;
    private final TraceCollector trace;

    public DeterministicReplayAgent(CustomerContextTools customerTools, KnowledgeRetrievalTools knowledgeTools,
                                    DeterministicRulesTools rulesTools, EscalationTools escalationTools,
                                    TicketMatchTools ticketMatchTools, TurnState turnState,
                                    TraceCollector trace) {
        this.customerTools = customerTools;
        this.knowledgeTools = knowledgeTools;
        this.rulesTools = rulesTools;
        this.escalationTools = escalationTools;
        this.ticketMatchTools = ticketMatchTools;
        this.turnState = turnState;
        this.trace = trace;
    }

    private static final String SUPERVISOR_OFFER = "I can ask a supervisor to review it";
    private static final Pattern AFFIRMATIVE = Pattern.compile(
            "^\\s*(yes|yeah|yep|sure|ok|okay|please|please do|go ahead|do it|do that)\\b.*", Pattern.CASE_INSENSITIVE);

    /** A thank-you or goodbye on its own: short, no question, no request, no problem report. */
    private static final Pattern CLOSING = Pattern.compile("\\b(thank you|thank u|thanks|thx|ty|cheers|many thanks"
            + "|appreciate (it|that|you|your help|the help)|that[\u2019']?s all|that is all|that[\u2019']?s it|all set|bye|goodbye"
            + "|good bye|have a (good|great|nice) (day|one|evening|weekend))\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_CLOSING = Pattern.compile("\\?|\\b(but|also|another|can you|could you|would you"
            + "|will you|please|what|why|how|when|where|need|want|still|not|no longer|doesn[\u2019']?t|isn[\u2019']?t|didn[\u2019']?t"
            + "|won[\u2019']?t|can[\u2019']?t|problem|issue|error|down|broken|for nothing)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * @return true for a closing message ("Thank you!", "ok thanks, that's all", "great, bye"): it is answered with a
     * short acknowledgement, without tools or a model call. "Thanks, but can you waive it?", "thanks, the gateway
     * is still down" and "thanks for nothing, get me a human" are not closings and get a full turn.
     */
    public static boolean isClosing(String message) {
        if (message == null || message.isBlank() || message.strip().split("\\s+").length > 12) {
            return false;
        }
        return CLOSING.matcher(message).find() && !NOT_CLOSING.matcher(message).find()
                && !EscalationTools.isHumanRequest(message);
    }

    /** Acknowledges a closing message; mentions the hand-off if this conversation was already escalated. */
    public String closing(List<Exchange> history) {
        trace.record("CONVERSATION_CLOSING: acknowledged without tools or a model call");
        boolean handedOff = history.stream().map(Exchange::agent)
                .anyMatch(a -> a.contains("Tier-2") || a.contains("asked a supervisor"));
        return handedOff
                ? "You're welcome. A Tier-2 specialist has your case and will contact you with the full context. "
                        + "Is there anything else I can help with?"
                : "You're welcome. Is there anything else I can help you with today?";
    }

    public String respond(String customerId, String message) {
        return respond(customerId, message, List.of());
    }

    /**
     * Multi-turn entry point (FR-6.6). Checked in order: the customer asks for a person (hand off in every
     * mode); the customer says yes to the supervisor review offered in the previous reply; a follow-up with no
     * topic of its own ("Why not? Can you make an exception?") is routed with the most recent earlier customer
     * message that had one. Otherwise the message is handled on its own, as in a single-turn conversation.
     */
    public String respond(String customerId, String message, List<Exchange> history) {
        if (EscalationTools.isHumanRequest(message)) {
            return humanHandoff(customerTools.getCustomerState(customerId), message, history, false);
        }
        if (isClosing(message)) {
            return closing(history); // before the "yes" check, so "ok thanks" is a thank-you, not a yes
        }
        if (!history.isEmpty() && history.get(history.size() - 1).agent().contains(SUPERVISOR_OFFER)
                && message.length() <= 60 && AFFIRMATIVE.matcher(message).matches()) {
            trace.record("CONVERSATION_FOLLOW_UP: customer accepted supervisor review");
            return humanHandoff(customerTools.getCustomerState(customerId), message, history, true);
        }
        if (!hasTopic(message)) {
            for (int i = history.size() - 1; i >= 0; i--) {
                String earlier = history.get(i).customer();
                if (hasTopic(earlier) && !EscalationTools.isHumanRequest(earlier)) {
                    trace.record("CONVERSATION_FOLLOW_UP: routed with the earlier topic");
                    return route(customerId, earlier + " " + message);
                }
            }
        }
        return route(customerId, message);
    }

    /** @return true if the message has an intent the planner can route (billing, policy, technical, status) */
    public static boolean hasTopic(String message) {
        String lower = message.toLowerCase();
        return BILLING_WORDS.stream().anyMatch(lower::contains) || POLICY_WORDS.stream().anyMatch(lower::contains)
                || TECH_WORDS.stream().anyMatch(lower::contains)
                || INVESTIGATION_WORDS.stream().anyMatch(lower::contains) || isStatusQuestion(lower, message);
    }

    /** Hand-off the customer asked for: no answer attempt, the Tier-2 card carries the conversation so far. */
    private String humanHandoff(CustomerProfile profile, String message, List<Exchange> history,
                                boolean acceptedSupervisorOffer) {
        List<String> steps = new ArrayList<>(List.of("Loaded customer profile"));
        TicketMatch existing = checkExistingTicket(profile, message, steps);
        for (Exchange e : history) {
            steps.add("Earlier in this conversation, customer: \"" + e.customer() + "\"");
        }
        steps.add(acceptedSupervisorOffer ? "Customer accepted the offer of a supervisor review"
                : "Customer asked to speak to a person");
        String intent = history.stream().map(Exchange::customer).filter(DeterministicReplayAgent::hasTopic)
                .reduce((a, b) -> b).map(DeterministicReplayAgent::intentOf).orElse("HUMAN_REQUESTED");
        EscalationTools.EscalationDecision esc = escalationTools.escalateWithSummary(
                profile.customerId(), message, intent, steps, acceptedSupervisorOffer);
        String handoff = acceptedSupervisorOffer
                ? "I have asked a supervisor to review it. They will contact you with the full context, so you "
                        + "will not need to repeat yourself."
                : escalationSentence(esc);
        return (existingTicketSentence(existing) + handoff).strip();
    }

    private static String intentOf(String message) {
        String lower = message.toLowerCase();
        if (TECH_WORDS.stream().anyMatch(lower::contains)) return "TECHNICAL_OUTAGE";
        if (POLICY_WORDS.stream().anyMatch(lower::contains)) return "POLICY_QUESTION";
        if (BILLING_WORDS.stream().anyMatch(lower::contains)) return "BILLING_WAIVER";
        if (isStatusQuestion(lower, message)) return "TICKET_STATUS";
        return "BILLING_INVESTIGATION";
    }

    private String route(String customerId, String message) {
        CustomerProfile profile = customerTools.getCustomerState(customerId);
        String lower = message.toLowerCase();
        boolean policyQuestion = POLICY_WORDS.stream().anyMatch(lower::contains) && !lower.contains("err-");
        boolean technical = !policyQuestion && TECH_WORDS.stream().anyMatch(lower::contains);
        boolean billing = BILLING_WORDS.stream().anyMatch(lower::contains);

        if (isStatusQuestion(lower, message) && !billing) {
            return ticketStatus(profile, message);
        }
        if (technical) {
            return technical(profile, message);
        }
        if (!billing && INVESTIGATION_WORDS.stream().anyMatch(lower::contains)) {
            return investigateBill(profile, message);
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

    /**
     * FR-3.10 multi-step billing investigation for "why is my bill so high?" questions. Each step is a
     * tool call or a check recorded in the trace: (1) ledger analysis, (2) open-ticket check, (3) waiver
     * eligibility for late fees, without applying it, (4) SLA-credit policy when an outage ticket is
     * open, (5) verification that every amount, invoice, ticket and citation in the answer comes from
     * the tools. A failed verification escalates instead of answering.
     */
    private String investigateBill(CustomerProfile profile, String message) {
        List<String> steps = new ArrayList<>(List.of("Loaded customer profile"));
        StringBuilder out = new StringBuilder("I looked into your bill. ");

        // 1. Ledger: what drove the increase (unpaid charges beyond the subscription)
        List<CustomerProfile.LedgerEntry> drivers = profile.recentCharges().stream()
                .filter(c -> "UNPAID".equalsIgnoreCase(c.status()) && !"SUBSCRIPTION".equalsIgnoreCase(c.chargeType()))
                .toList();
        BigDecimal total = drivers.stream().map(CustomerProfile.LedgerEntry::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        trace.record("INVESTIGATE 1/5 billing: " + drivers.size() + " unpaid charge(s) beyond subscription, total $" + total);
        steps.add("Ledger: " + drivers.size() + " unpaid non-subscription charge(s), $" + total);
        if (drivers.isEmpty()) {
            out.append("There are no unpaid charges beyond your subscription on your account. ");
        } else {
            out.append("Beyond your subscription there ").append(drivers.size() == 1 ? "is 1 unpaid charge" : "are "
                    + drivers.size() + " unpaid charges").append(", $").append(total).append(" in total: ");
            out.append(String.join("; ", drivers.stream().map(c -> c.chargeType() + " $" + c.amount()
                    + " (" + c.invoiceId() + ")").toList())).append(". ");
        }

        // 2. Tickets: is this already being worked on?
        TicketMatch existing = checkExistingTicket(profile, message, steps);
        List<CustomerProfile.TicketSummary> open = profile.activeTickets().stream()
                .filter(t -> !CLOSED.contains(t.status())).toList();
        trace.record("INVESTIGATE 2/5 tickets: " + open.size() + " open"
                + (existing.matched() ? ", follow-up on " + existing.ticketId() : ""));
        out.append(existingTicketSentence(existing));

        // 3. Remedy for late fees: eligibility only, nothing is applied
        for (CustomerProfile.LedgerEntry fee : drivers) {
            if (!"LATE_FEE".equalsIgnoreCase(fee.chargeType())) continue;
            knowledgeTools.searchPoliciesAndCases("late fee waiver policy eligibility");
            WaiverEvaluationResult eval = rulesTools.checkEligibility(profile.customerId(), fee.amount());
            steps.add("Late fee " + fee.invoiceId() + " waiver eligibility: " + eval.reasonCode() + " (not applied)");
            out.append(eval.eligible()
                    ? "The $" + fee.amount() + " late fee is eligible for a one-time waiver under [" + eval.policyCitation()
                            + "]. Reply \"waive my late fee\" and I will apply it. "
                    : "The $" + fee.amount() + " late fee cannot be waived under [" + eval.policyCitation() + "]: "
                            + eval.explanation() + " ");
        }
        trace.record("INVESTIGATE 3/5 remedies: late-fee eligibility checked, nothing applied");

        // 4. Outage on record: point to the SLA-credit policy
        CustomerProfile.TicketSummary outage = open.stream()
                .filter(t -> OUTAGE_CATEGORIES.contains(t.category())).findFirst().orElse(null);
        if (outage != null) {
            SearchResponse sla = knowledgeTools.searchPoliciesAndCases("SLA credit for a P1 outage longer than 4 hours");
            SearchResult clause = sla.results().stream().filter(r -> !r.superseded()).findFirst().orElse(null);
            if (!sla.lowConfidence() && clause != null) {
                out.append("Your open ticket ").append(outage.ticketId()).append(" (\"").append(outage.subject())
                        .append("\") may also qualify for an SLA credit under [").append(clause.documentId())
                        .append("]; credits are confirmed by the account team once the incident is closed. ");
                steps.add("Outage ticket " + outage.ticketId() + ": SLA credit policy " + clause.documentId());
            }
        }
        trace.record("INVESTIGATE 4/5 policy: " + (outage == null ? "no outage ticket" : "SLA credit checked for " + outage.ticketId()));

        // 5. Verify every fact in the answer against the tool results
        String answer = out.toString();
        List<String> failures = verify(answer, profile);
        trace.record(failures.isEmpty()
                ? "INVESTIGATE 5/5 VERIFIED: amounts, invoices, tickets and citations match tool results"
                : "INVESTIGATE 5/5 VERIFICATION_FAILED: " + failures);
        EscalationTools.EscalationDecision esc = escalationTools.escalateWithSummary(
                profile.customerId(), message, "BILLING_INVESTIGATION", steps, !failures.isEmpty());
        if (esc.escalated()) {
            answer += escalationSentence(esc);
        }
        return answer.strip();
    }

    private static final Pattern MONEY = Pattern.compile("\\$(\\d+(?:\\.\\d{2})?)");
    private static final Pattern INVOICE = Pattern.compile("\\b(INV-[A-Z0-9-]+)\\b");
    private static final Pattern TICKET = Pattern.compile("\\b(TKT-[A-Z0-9]+)\\b");
    private static final Pattern CITE = Pattern.compile("\\[([A-Za-z0-9_.\\-]+#[A-Za-z0-9_.\\-]+)]");

    /** Every amount must be a ledger amount or the driver total; every invoice, ticket and citation must exist. */
    public List<String> verify(String answer, CustomerProfile profile) {
        List<String> failures = new ArrayList<>();
        Set<BigDecimal> amounts = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (CustomerProfile.LedgerEntry c : profile.recentCharges()) {
            amounts.add(c.amount().stripTrailingZeros());
            if ("UNPAID".equalsIgnoreCase(c.status()) && !"SUBSCRIPTION".equalsIgnoreCase(c.chargeType())) total = total.add(c.amount());
        }
        amounts.add(total.stripTrailingZeros());
        Matcher m = MONEY.matcher(answer);
        while (m.find()) if (!amounts.contains(new BigDecimal(m.group(1)).stripTrailingZeros())) failures.add("amount $" + m.group(1));
        Set<String> invoices = new HashSet<>(profile.recentCharges().stream().map(CustomerProfile.LedgerEntry::invoiceId).toList());
        m = INVOICE.matcher(answer);
        while (m.find()) if (!invoices.contains(m.group(1))) failures.add("invoice " + m.group(1));
        Set<String> tickets = new HashSet<>(profile.activeTickets().stream().map(CustomerProfile.TicketSummary::ticketId).toList());
        m = TICKET.matcher(answer);
        while (m.find()) if (!tickets.contains(m.group(1))) failures.add("ticket " + m.group(1));
        Set<String> retrieved = turnState.getRetrievedDocIds();
        m = CITE.matcher(answer);
        while (m.find()) if (!retrieved.contains(m.group(1))) failures.add("citation " + m.group(1));
        return failures;
    }

    // -------------------------------------------------------------------------

    private static final List<String> STATUS_WORDS = List.of(
            "status of my ticket", "ticket status", "status of ticket", "update on my ticket", "update on ticket",
            "my ticket", "my tickets", "my case", "where are we with", "any update on");
    private static final Pattern TICKET_ID = Pattern.compile("\\bTKT-[A-Z0-9]+\\b", Pattern.CASE_INSENSITIVE);

    /** "What's the status of my ticket?" questions, or a message naming a ticket id (FR-6.5). */
    static boolean isStatusQuestion(String lower, String message) {
        return STATUS_WORDS.stream().anyMatch(lower::contains) || TICKET_ID.matcher(message).find();
    }

    /**
     * FR-6.5 ticket status: lists the customer's open tickets (or the one they named) with status, SLA deadline
     * and time left or breached. The existing SLA-breach escalation runs as usual, so a breached ticket goes to
     * Tier-2 with the handoff card.
     */
    private String ticketStatus(CustomerProfile profile, String message) {
        List<String> steps = new ArrayList<>(List.of("Loaded customer profile"));
        List<CustomerProfile.TicketSummary> open = profile.activeTickets().stream()
                .filter(t -> !CLOSED.contains(t.status())).toList();
        Matcher named = TICKET_ID.matcher(message);
        String wanted = named.find() ? named.group().toUpperCase() : null;
        List<CustomerProfile.TicketSummary> shown = wanted == null ? open
                : profile.activeTickets().stream().filter(t -> t.ticketId().equalsIgnoreCase(wanted)).toList();
        trace.record("TICKET_STATUS: " + shown.size() + " ticket(s) " + (wanted == null ? "open" : "matching " + wanted));

        StringBuilder out = new StringBuilder();
        if (wanted != null && shown.isEmpty()) {
            out.append("I could not find ticket ").append(wanted).append(" on your account. ");
            shown = open;
        }
        if (shown.isEmpty()) {
            out.append("You have no open support tickets right now. If something is not working, tell me what you "
                    + "are seeing and I will help.");
        } else {
            out.append(shown.size() == 1 ? "Here is your open ticket: " : "Here are your " + shown.size() + " open tickets: ");
            Instant now = Instant.now();
            List<String> lines = new ArrayList<>();
            for (CustomerProfile.TicketSummary t : shown) {
                lines.add(t.ticketId() + " \"" + t.subject() + "\" is " + t.status() + ", " + slaText(t.slaDeadline(), now));
                steps.add("Ticket " + t.ticketId() + " " + t.status() + ", SLA " + t.slaDeadline());
            }
            out.append(String.join("; ", lines)).append(". ");
        }
        EscalationTools.EscalationDecision esc = escalationTools.escalateWithSummary(
                profile.customerId(), message, "TICKET_STATUS", steps, false);
        if (esc.escalated()) {
            out.append(escalationSentence(esc));
        }
        return out.toString().strip();
    }

    private static String slaText(String deadline, Instant now) {
        Instant due = EscalationTools.parseInstant(deadline);
        if (due == null) {
            return "no SLA deadline on record";
        }
        long hours = Duration.between(now, due).toHours();
        if (due.isBefore(now)) {
            long late = Math.max(1, -hours);
            return "SLA breached " + late + (late == 1 ? " hour" : " hours") + " ago";
        }
        return hours >= 48 ? "within SLA (" + (hours / 24) + " days left)"
                : "within SLA (" + Math.max(hours, 0) + (hours == 1 ? " hour" : " hours") + " left)";
    }

    // -------------------------------------------------------------------------

    /**
     * Agent planner mode: escalation must not depend on the model remembering to call the tool. If the model
     * did not run {@code escalateWithSummary}, Java runs it (it re-checks SLA breach, frustration and retrieval
     * confidence itself) and returns the sentence to append, or {@code null} when no escalation is needed.
     */
    public String ensureEscalationChecked(String customerId, String message) {
        boolean ran = turnState.getToolsExecuted().contains("escalateWithSummary");
        // A request for a person is honoured even if the model checked escalation and decided against it.
        boolean humanRequestMissed = EscalationTools.isHumanRequest(message) && !turnState.isEscalated();
        if (ran && !humanRequestMissed) {
            return null;
        }
        // A waiver that needs a supervisor (tenure too short) is forced, as in the deterministic plan.
        boolean needsSupervisor = "TENURE_TOO_SHORT".equals(turnState.getLastWaiverReasonCode());
        EscalationTools.EscalationDecision esc = escalationTools.escalateWithSummary(
                customerId, message, "AGENT_PLANNED", List.of("Escalation check enforced by Java after the model's plan"),
                needsSupervisor);
        trace.record("AGENTIC_ESCALATION_ENFORCED: " + esc.reason());
        return esc.escalated() ? escalationSentence(esc) : null;
    }

    /**
     * Agent planner fallback: when the model already escalated before its last call failed, the deterministic
     * reply must say so instead of offering a supervisor review that has already been requested.
     */
    public static String reconcileEscalation(String reply, boolean escalatedByModel) {
        if (!escalatedByModel || reply.contains("Tier-2") || reply.contains("asked a supervisor")) {
            return reply;
        }
        String offer = " If you would like, " + SUPERVISOR_OFFER + ".";
        return reply.replace(offer, "").strip()
                + " I have passed your case to a Tier-2 specialist with the full context, so you will not need to "
                + "repeat yourself.";
    }

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
        if (r.startsWith("CUSTOMER_REQUESTED_HUMAN")) {
            return "Of course. I have passed your conversation to a Tier-2 specialist, who will contact you with "
                    + "the full context, so you will not need to repeat yourself.";
        }
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
