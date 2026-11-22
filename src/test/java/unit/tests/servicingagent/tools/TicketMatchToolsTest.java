package unit.tests.servicingagent.tools;

import unit.tests.servicingagent.model.CustomerProfile;
import unit.tests.servicingagent.model.CustomerProfile.TicketSummary;
import unit.tests.servicingagent.tools.TicketMatchTools.TicketMatch;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-6.3 (agent recognises an existing ticket) and FR-6.4 (no duplicate ticket on creation). */
class TicketMatchToolsTest {

    private static final TicketSummary GATEWAY = new TicketSummary("TKT-8841", "GATEWAY_OUTAGE",
            "Production API Gateway throwing ERR-504-GW", "PENDING_ENGINEERING", "2026-11-20T10:00:00Z");
    private static final TicketSummary BILLING = new TicketSummary("TKT-7721", "BILLING",
            "Disputing October overage", "OPEN", "2026-11-25T10:00:00Z");
    private static final TicketSummary RESOLVED_GATEWAY = new TicketSummary("TKT-1000", "GATEWAY_OUTAGE",
            "EU gateway ERR-504-GW", "RESOLVED", "2026-01-01T10:00:00Z");

    private static CustomerProfile profile(TicketSummary... tickets) {
        return new CustomerProfile("cust_1", "Test", "Gold", 24, BigDecimal.TEN, 0, 1L, List.of(), List.of(tickets));
    }

    // --- FR-6.3: customer message vs open tickets --------------------------------------------

    @Test
    void errorCodeInMessageMatchesOpenTicket() {
        TicketMatch m = TicketMatchTools.match(profile(BILLING, GATEWAY), "Still getting err-504-gw, any update?");
        assertThat(m.matched()).isTrue();
        assertThat(m.ticketId()).isEqualTo("TKT-8841");
        assertThat(m.matchReason()).isEqualTo("ERROR_CODE");
    }

    @Test
    void ticketIdInMessageMatches() {
        TicketMatch m = TicketMatchTools.match(profile(GATEWAY, BILLING), "What is happening with tkt-7721?");
        assertThat(m.ticketId()).isEqualTo("TKT-7721");
        assertThat(m.matchReason()).isEqualTo("TICKET_ID");
    }

    @Test
    void sameTopicMatchesBillingDispute() {
        TicketMatch m = TicketMatchTools.match(profile(BILLING), "I was overcharged $120 again and I am fed up");
        assertThat(m.ticketId()).isEqualTo("TKT-7721");
        assertThat(m.matchReason()).isEqualTo("SAME_TOPIC");
    }

    @Test
    void waiverRequestIsANewRequestEvenWithBillingTicketOpen() {
        assertThat(TicketMatchTools.match(profile(BILLING), "Can you waive my late fee? It looks like an overcharge")
                .matched()).isFalse();
    }

    @Test
    void resolvedTicketsAndUnrelatedMessagesNeverMatch() {
        assertThat(TicketMatchTools.match(profile(RESOLVED_GATEWAY), "ERR-504-GW is back").matched()).isFalse();
        assertThat(TicketMatchTools.match(profile(GATEWAY), "What SLA credit do I get for a P1 outage?")
                .matched()).isFalse(); // policy question, not a follow-up
        assertThat(TicketMatchTools.match(profile(GATEWAY), "What is my latest charge?").matched()).isFalse();
        assertThat(TicketMatchTools.match(profile(), "ERR-504-GW").matched()).isFalse();
    }

    // --- FR-6.4: new ticket vs open tickets ---------------------------------------------------

    @Test
    void newTicketWithSameErrorCodeIsDuplicate() {
        TicketMatch d = TicketMatchTools.duplicateOf(List.of(GATEWAY), "TECHNICAL", "ERR-504-GW on the EU edge");
        assertThat(d.matched()).isTrue();
        assertThat(d.ticketId()).isEqualTo("TKT-8841");
        assertThat(d.matchReason()).isEqualTo("ERROR_CODE");
    }

    @Test
    void sameCategoryAndSubjectIgnoringCaseAndPunctuationIsDuplicate() {
        TicketSummary t = new TicketSummary("TKT-1", "ACCOUNT", "Reset my SSO login", "OPEN", "");
        TicketMatch d = TicketMatchTools.duplicateOf(List.of(t), "account", "  reset my sso-login! ");
        assertThat(d.matchReason()).isEqualTo("SAME_SUBJECT");
    }

    @Test
    void sameCategoryFamilyAndTopicIsDuplicate() {
        TicketMatch d = TicketMatchTools.duplicateOf(List.of(BILLING), "BILLING", "Customer says they were overcharged");
        assertThat(d.ticketId()).isEqualTo("TKT-7721");
        assertThat(d.matchReason()).isEqualTo("SAME_TOPIC");
    }

    @Test
    void differentProblemOrClosedTicketIsNotDuplicate() {
        assertThat(TicketMatchTools.duplicateOf(List.of(GATEWAY), "BILLING", "Disputing October overage").matched())
                .isFalse();
        assertThat(TicketMatchTools.duplicateOf(List.of(BILLING), "ACCOUNT", "Add a new admin user").matched())
                .isFalse();
        assertThat(TicketMatchTools.duplicateOf(List.of(RESOLVED_GATEWAY), "GATEWAY_OUTAGE", "EU gateway ERR-504-GW")
                .matched()).isFalse();
    }
}
