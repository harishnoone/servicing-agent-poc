package unit.tests.servicingagent.tools;

import unit.tests.servicingagent.model.CustomerProfile;
import unit.tests.servicingagent.model.CustomerProfile.TicketSummary;
import unit.tests.servicingagent.telemetry.TraceCollector;
import unit.tests.servicingagent.telemetry.TurnState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** FR-6.1 triggers and FR-6.2 handoff card contents. */
class EscalationToolsTest {

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-18T12:00:00Z"), ZoneOffset.UTC);

    private final CustomerContextTools customers = mock(CustomerContextTools.class);
    private final TurnState turnState = new TurnState();
    private final EscalationTools tools =
            new EscalationTools(customers, new TraceCollector(), turnState, 0.72, NOW);

    private void givenTickets(TicketSummary... tickets) {
        when(customers.loadProfile("c1")).thenReturn(new CustomerProfile("c1", "Marcus", "Enterprise", 48,
                new BigDecimal("2400.00"), 1, 0L, List.of(), List.of(tickets)));
    }

    @Test
    void slaBreachOnDebeziumTimestampEscalatesWithFullCard() {
        givenTickets(new TicketSummary("TKT-8841", "GATEWAY_OUTAGE", "ERR-504-GW", "PENDING_ENGINEERING",
                "2026-11-18T08:00:00.000000Z"));
        turnState.recordTool("getCustomerState");
        turnState.recordRetrieval(List.of("troubleshooting_guide.md#sec-1.1"), 0.9);

        var d = tools.escalateWithSummary("c1", "gateway still down", "TECHNICAL_OUTAGE", List.of("Searched KB"), false);

        assertThat(d.escalated()).isTrue();
        assertThat(d.reason()).isEqualTo("SLA_BREACH");
        var card = d.handoffCard();
        assertThat(card.tier()).isEqualTo("Enterprise");
        assertThat(card.tenureMonths()).isEqualTo(48);
        assertThat(card.mrr()).isEqualByComparingTo("2400.00");
        assertThat(card.openTickets()).extracting(TicketSummary::ticketId).containsExactly("TKT-8841");
        assertThat(card.classifiedIntent()).isEqualTo("TECHNICAL_OUTAGE");
        assertThat(card.urgencyScore()).isGreaterThan(0.9);
        assertThat(card.toolsExecuted()).containsExactly("getCustomerState", "escalateWithSummary");
        assertThat(card.policyCitations()).containsExactly("troubleshooting_guide.md#sec-1.1");
        assertThat(turnState.isEscalated()).isTrue();
    }

    @Test
    void postgresTextTimestampIsParsed() {
        assertThat(EscalationTools.parseInstant("2025-06-01 13:00:00+00")).isEqualTo(Instant.parse("2025-06-01T13:00:00Z"));
        assertThat(EscalationTools.parseInstant("")).isNull();
        assertThat(EscalationTools.parseInstant("garbage")).isNull();
    }

    @Test
    void futureSlaAndResolvedTicketsDoNotEscalate() {
        givenTickets(
                new TicketSummary("T1", "BILLING", "s", "OPEN", "2026-11-21T12:00:00Z"),
                new TicketSummary("T2", "BILLING", "s", "RESOLVED", "2026-01-01T00:00:00Z"));
        var d = tools.escalateWithSummary("c1", "can you check my invoice", "BILLING_QUESTION", List.of(), false);
        assertThat(d.escalated()).isFalse();
        assertThat(d.handoffCard()).isNull();
        assertThat(turnState.isEscalated()).isFalse();
    }

    @Test
    void frustrationEscalates() {
        givenTickets();
        var d = tools.escalateWithSummary("c1", "This is unacceptable, I am fed up", "BILLING_WAIVER", List.of(), false);
        assertThat(d.reason()).isEqualTo("HIGH_FRUSTRATION");
    }

    @Test
    void lowRetrievalConfidenceEscalates() {
        givenTickets();
        turnState.recordRetrieval(List.of(), 0.3);
        var d = tools.escalateWithSummary("c1", "question", "GENERAL_INQUIRY", List.of(), false);
        assertThat(d.reason()).isEqualTo("LOW_RETRIEVAL_CONFIDENCE");
    }
}
