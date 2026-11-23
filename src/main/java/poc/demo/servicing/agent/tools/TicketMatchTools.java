package poc.demo.servicing.agent.tools;

import poc.demo.servicing.agent.model.CustomerProfile;
import poc.demo.servicing.agent.model.CustomerProfile.TicketSummary;
import poc.demo.servicing.agent.telemetry.TraceCollector;
import poc.demo.servicing.agent.telemetry.TurnState;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises when a customer is asking about something they already have an open ticket for
 * (FR-6.3), so the agent treats the message as a follow-up instead of a new request.
 *
 * <p>Deterministic, like the other decision tools: the same message and profile always give the
 * same answer, and every match states why. Rules, in order:
 * <ol>
 *   <li>{@code TICKET_ID}: the message names an open ticket id (e.g. {@code TKT-8841}).</li>
 *   <li>{@code ERROR_CODE}: an error code in the message (e.g. {@code ERR-504-GW}) appears in an
 *       open ticket's subject.</li>
 *   <li>{@code SAME_TOPIC}: the message is about the same topic as an open ticket's category
 *       (technical fault or billing dispute). Waiver requests and policy questions never match on
 *       topic alone: asking for a fee waiver or what a policy says is a new request even when a
 *       related ticket is open.</li>
 * </ol>
 * Resolved and closed tickets never match. The newest matching ticket wins.
 */
@Component
public class TicketMatchTools {

    private static final Set<String> CLOSED_STATUSES = Set.of("RESOLVED", "CLOSED");
    private static final Pattern TICKET_ID = Pattern.compile("\\b(TKT-\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ERROR_CODE = Pattern.compile("\\b(ERR-[A-Z0-9]+(?:-[A-Z0-9]+)*)\\b",
            Pattern.CASE_INSENSITIVE);

    static final List<String> TECHNICAL_WORDS = List.of(
            "err-", "error", "gateway", "outage", "timeout", "certificate", "is down", "not working", "failing");
    static final List<String> BILLING_DISPUTE_WORDS = List.of(
            "overcharg", "overage", "charged twice", "double charge", "double-charge", "dispute",
            "discrepancy", "wrong charge", "incorrect charge", "billing error", "invoice error");
    static final List<String> WAIVER_WORDS = List.of("waive", "waiver");
    static final List<String> POLICY_QUESTION_WORDS = List.of("policy", "credit", "entitle", "eligib");

    private static final Set<String> TECHNICAL_CATEGORIES = Set.of("TECHNICAL", "GATEWAY_OUTAGE", "OUTAGE", "INCIDENT");
    private static final Set<String> BILLING_CATEGORIES = Set.of("BILLING", "BILLING_DISPUTE");

    private final CustomerContextTools customerContextTools;
    private final TraceCollector traceCollector;
    private final TurnState turnState;

    public TicketMatchTools(CustomerContextTools customerContextTools, TraceCollector traceCollector,
                            TurnState turnState) {
        this.customerContextTools = customerContextTools;
        this.traceCollector = traceCollector;
        this.turnState = turnState;
    }

    @Tool(description = "Check whether the customer already has an open support ticket for this request. "
            + "Call it after getCustomerState for any problem report or complaint. If matched=true, tell the "
            + "customer you are treating the message as a follow-up on that ticket (give its id and status) and "
            + "do not suggest opening a new ticket.")
    public TicketMatch findMatchingOpenTicket(
            @ToolParam(description = "Customer account ID, e.g. cust_456") String customerId,
            @ToolParam(description = "The customer's latest message, verbatim") String customerMessage) {
        long start = System.currentTimeMillis();
        CustomerProfile profile = customerContextTools.loadProfile(customerId);
        TicketMatch match = match(profile, customerMessage);
        turnState.recordTool("findMatchingOpenTicket");
        turnState.recordTicketMatch(match);
        traceCollector.record(match.matched()
                ? "TOOL_CALL: findMatchingOpenTicket -> EXISTING_TICKET " + match.ticketId() + " (" + match.matchReason() + ")"
                : "TOOL_CALL: findMatchingOpenTicket -> NO_MATCH", System.currentTimeMillis() - start);
        return match;
    }

    /** Pure matching logic, package-visible for unit tests. */
    static TicketMatch match(CustomerProfile profile, String message) {
        String text = message == null ? "" : message;
        String lower = text.toLowerCase(Locale.ROOT);
        List<TicketSummary> open = profile.activeTickets().stream()
                .filter(t -> !CLOSED_STATUSES.contains(upper(t.status())))
                .toList();
        if (open.isEmpty()) {
            return TicketMatch.none();
        }

        Matcher id = TICKET_ID.matcher(text);
        while (id.find()) {
            String wanted = id.group(1).toUpperCase(Locale.ROOT);
            for (TicketSummary t : open) {
                if (wanted.equalsIgnoreCase(t.ticketId())) return TicketMatch.of(t, "TICKET_ID");
            }
        }

        Matcher code = ERROR_CODE.matcher(text);
        while (code.find()) {
            String wanted = code.group(1).toUpperCase(Locale.ROOT);
            for (TicketSummary t : open) {
                if (upper(t.subject()).contains(wanted)) return TicketMatch.of(t, "ERROR_CODE");
            }
        }

        boolean waiverRequest = WAIVER_WORDS.stream().anyMatch(lower::contains);
        boolean policyQuestion = POLICY_QUESTION_WORDS.stream().anyMatch(lower::contains);
        boolean technical = TECHNICAL_WORDS.stream().anyMatch(lower::contains);
        boolean billingDispute = BILLING_DISPUTE_WORDS.stream().anyMatch(lower::contains);
        if (!waiverRequest && !policyQuestion) {
            for (TicketSummary t : open) {
                String category = upper(t.category());
                if ((technical && TECHNICAL_CATEGORIES.contains(category))
                        || (billingDispute && BILLING_CATEGORIES.contains(category))) {
                    return TicketMatch.of(t, "SAME_TOPIC");
                }
            }
        }
        return TicketMatch.none();
    }

    /**
     * Duplicate check for ticket creation (FR-6.4): would a new ticket with this category and subject
     * duplicate one of the customer's open tickets? Uses the same definition of "same problem" as
     * {@link #match}: a shared error code, the same category with the same subject (ignoring case,
     * punctuation and spacing), or the same category family and topic.
     */
    public static TicketMatch duplicateOf(List<TicketSummary> openTickets, String category, String subject) {
        String newCategory = upper(category);
        String newSubject = subject == null ? "" : subject;
        List<TicketSummary> open = openTickets.stream()
                .filter(t -> !CLOSED_STATUSES.contains(upper(t.status())))
                .toList();

        Matcher code = ERROR_CODE.matcher(newSubject);
        while (code.find()) {
            String wanted = code.group(1).toUpperCase(Locale.ROOT);
            for (TicketSummary t : open) {
                if (upper(t.subject()).contains(wanted)) return TicketMatch.of(t, "ERROR_CODE");
            }
        }
        for (TicketSummary t : open) {
            if (newCategory.equals(upper(t.category())) && normalize(newSubject).equals(normalize(t.subject()))) {
                return TicketMatch.of(t, "SAME_SUBJECT");
            }
        }
        String newFamily = family(newCategory);
        String topic = topic(newSubject.toLowerCase(Locale.ROOT));
        if (newFamily != null && newFamily.equals(topic)) {
            for (TicketSummary t : open) {
                if (newFamily.equals(family(upper(t.category())))) return TicketMatch.of(t, "SAME_TOPIC");
            }
        }
        return TicketMatch.none();
    }

    private static String family(String category) {
        if (TECHNICAL_CATEGORIES.contains(category)) return "TECHNICAL";
        if (BILLING_CATEGORIES.contains(category)) return "BILLING";
        return null;
    }

    /** Topic of a free-text message or subject, or {@code null} when it names no known problem type. */
    private static String topic(String lower) {
        if (BILLING_DISPUTE_WORDS.stream().anyMatch(lower::contains)) return "BILLING";
        if (TECHNICAL_WORDS.stream().anyMatch(lower::contains)) return "TECHNICAL";
        return null;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }

    /**
     * @param matched     true when an open ticket already covers this request
     * @param ticketId    matching ticket id, or {@code null}
     * @param status      its status, e.g. {@code PENDING_ENGINEERING}
     * @param subject     its subject
     * @param slaDeadline its SLA deadline as stored on the profile
     * @param matchReason {@code TICKET_ID}, {@code ERROR_CODE}, {@code SAME_TOPIC} or {@code NO_MATCH}
     */
    public record TicketMatch(boolean matched, String ticketId, String status, String subject,
                              String slaDeadline, String matchReason) {
        static TicketMatch none() {
            return new TicketMatch(false, null, null, null, null, "NO_MATCH");
        }

        static TicketMatch of(TicketSummary t, String reason) {
            return new TicketMatch(true, t.ticketId(), t.status(), t.subject(), t.slaDeadline(), reason);
        }
    }
}
