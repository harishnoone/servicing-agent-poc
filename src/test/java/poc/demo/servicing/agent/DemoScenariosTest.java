package poc.demo.servicing.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import poc.demo.servicing.agent.actions.ActionPublisher;
import poc.demo.servicing.agent.admin.AuditService;
import poc.demo.servicing.agent.cache.WarehouseFallbackRepository;
import poc.demo.servicing.agent.dlt.DltService;
import poc.demo.servicing.agent.model.CustomerProfile;
import poc.demo.servicing.agent.model.CustomerProfile.LedgerEntry;
import poc.demo.servicing.agent.model.CustomerProfile.TicketSummary;
import poc.demo.servicing.agent.support.FakeRedis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs the three demo scenarios plus the cache-miss path end to end through
 * POST /api/agent/chat with the offline deterministic planner (NFR-1).
 * Redis is an in-memory fake; Postgres-backed collaborators are mocked; Kafka listeners are off.
 */
@SpringBootTest(properties = {
        "agent.llm.mode=replay",
        "agent.kafka.retry-topics.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "agent.debezium.connect-url="
})
@AutoConfigureMockMvc
class DemoScenariosTest {

    static final FakeRedis REDIS = new FakeRedis();

    @TestConfiguration
    static class RedisConfig {
        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return REDIS.template;
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean AuditService auditService;
    @MockitoBean ActionPublisher actionPublisher;
    @MockitoBean WarehouseFallbackRepository warehouse;
    @MockitoBean DltService dltService;

    @BeforeEach
    void seed() throws Exception {
        REDIS.store.clear();
        put(new CustomerProfile("cust_123", "Elena Vance", "Gold", 36, new BigDecimal("499.00"), 0, 101L,
                List.of(new LedgerEntry("INV-A", "LATE_FEE", new BigDecimal("45.00"), "UNPAID", "Late fee")),
                List.of(new TicketSummary("TKT-7721", "BILLING", "Invoice review", "OPEN",
                        Instant.now().plus(3, ChronoUnit.DAYS).toString()))));
        put(new CustomerProfile("cust_456", "Marcus Sterling", "Enterprise", 48, new BigDecimal("2400.00"), 1, 202L,
                List.of(new LedgerEntry("INV-2025-456-001", "LATE_FEE", new BigDecimal("35.00"), "UNPAID", "Late fee")),
                List.of(new TicketSummary("TKT-8841", "GATEWAY_OUTAGE", "Production API Gateway throwing ERR-504-GW",
                        "PENDING_ENGINEERING", Instant.now().minus(4, ChronoUnit.HOURS).toString()))));
        put(new CustomerProfile("cust_789", "Jordan Blake", "Standard", 6, new BigDecimal("49.00"), 0, 303L,
                List.of(new LedgerEntry("INV-2025-789-001", "LATE_FEE", new BigDecimal("25.00"), "UNPAID", "Late fee")),
                List.of()));
    }

    private void put(CustomerProfile p) throws Exception {
        REDIS.store.put("customer:profile:" + p.customerId(), mapper.writeValueAsString(p));
    }

    private JsonNode chat(String customerId, String message) throws Exception {
        String body = mapper.writeValueAsString(Map.of("customerId", customerId, "message", message));
        String json = mvc.perform(post("/api/agent/chat").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json);
    }

    private JsonNode chatIn(String conversationId, String customerId, String message) throws Exception {
        String body = mapper.writeValueAsString(
                Map.of("customerId", customerId, "message", message, "conversationId", conversationId));
        String json = mvc.perform(post("/api/agent/chat").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json);
    }

    private static List<String> events(JsonNode report) {
        return report.path("agentReasoningTrace").findValuesAsText("event");
    }

    @Test
    void scenario1_eligibleWaiverResolvesAutonomously() throws Exception {
        JsonNode r = chat("cust_123", "Why is my bill $45 higher, can you waive it?");

        assertThat(r.at("/kpiImpact/deflectionStatus").asText()).isEqualTo("RESOLVED_AUTONOMOUSLY");
        assertThat(r.at("/kpiImpact/estimatedAhtSavedMinutes").asDouble()).isEqualTo(6.5);
        assertThat(r.path("citations").get(0).asText()).isEqualTo("billing_waivers_policy.md#sec-4.2");
        assertThat(r.path("finalResponse").asText()).contains("[billing_waivers_policy.md#sec-4.2]");
        assertThat(r.path("handoffCard").isNull()).isTrue();
        assertThat(r.at("/customerSnapshot/accountTier").asText()).isEqualTo("Gold");
        assertThat(events(r)).first().isEqualTo("PII_SANITIZATION_PASSED");
        assertThat(events(r)).contains("TOOL_CALL: evaluateWaiverRules -> APPROVED")
                .anyMatch(e -> e.startsWith("TOOL_CALL: getCustomerState (REDIS_HIT)"))
                .anyMatch(e -> e.startsWith("TOOL_CALL: searchPoliciesAndCases"))
                .doesNotContain("COMPLIANCE_VIOLATION_RECORDED");
        verify(actionPublisher).publish(eq("waiver"), eq("cust_123"), eq("INV-A"), argThat(a -> a.compareTo(new BigDecimal("45")) == 0), eq("APPROVED"), eq("agent:tier1"), any(), any());
    }

    @Test
    void scenario2_slaBreachAndErr504EscalatesWithHandoffCard() throws Exception {
        JsonNode r = chat("cust_456", "Our production gateway keeps returning ERR-504-GW. What is the fix?");

        assertThat(r.at("/kpiImpact/deflectionStatus").asText()).isEqualTo("ESCALATED");
        assertThat(r.at("/kpiImpact/estimatedAhtSavedMinutes").asDouble()).isZero();
        JsonNode card = r.path("handoffCard");
        assertThat(card.path("detectedSentiment").asText()).isEqualTo("SLA_BREACH");
        assertThat(card.path("tier").asText()).isEqualTo("Enterprise");
        assertThat(card.path("mrr").decimalValue()).isEqualByComparingTo("2400.00");
        assertThat(card.path("openTickets").get(0).path("ticketId").asText()).isEqualTo("TKT-8841");
        assertThat(card.path("classifiedIntent").asText()).isEqualTo("TECHNICAL_OUTAGE");
        assertThat(mapper.convertValue(card.path("toolsExecuted"), List.class))
                .containsExactly("getCustomerState", "findMatchingOpenTicket", "searchPoliciesAndCases", "escalateWithSummary");
        assertThat(mapper.convertValue(card.path("policyCitations"), List.class))
                .contains("troubleshooting_guide.md#sec-1.1");

        String text = r.path("finalResponse").asText();
        assertThat(text).contains("[troubleshooting_guide.md#sec-1.1]").contains("superseded");
        assertThat(mapper.convertValue(r.path("citations"), List.class)).contains("troubleshooting_guide.md#sec-1.1");
    }

    @Test
    void scenario3_standardTierDeniedCleanlyWithoutEscalation() throws Exception {
        JsonNode r = chat("cust_789", "Can you waive my late fee?");

        assertThat(r.at("/kpiImpact/deflectionStatus").asText()).isEqualTo("RESOLVED_AUTONOMOUSLY");
        assertThat(events(r)).contains("TOOL_CALL: evaluateWaiverRules -> TIER_INELIGIBLE");
        assertThat(r.path("finalResponse").asText()).contains("cannot waive").contains("$25.00");
        assertThat(r.path("handoffCard").isNull()).isTrue();
        verify(actionPublisher, never()).publish(eq("waiver"), eq("cust_789"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void secondWaiverForMarcusIsDenied() throws Exception {
        JsonNode r = chat("cust_456", "Please waive the $35 late fee");
        assertThat(events(r)).contains("TOOL_CALL: evaluateWaiverRules -> WAIVER_LIMIT_REACHED");
        assertThat(r.path("finalResponse").asText()).doesNotContainIgnoringCase("qualify");
    }

    @Test
    void cacheMissUsesWarehouseFallback() throws Exception {
        when(warehouse.findProfile("cust_900")).thenReturn(Optional.of(new CustomerProfile("cust_900", "Dormant Dana",
                "Gold", 60, BigDecimal.TEN, 0, 0L,
                List.of(new LedgerEntry("INV-9", "LATE_FEE", new BigDecimal("20.00"), "UNPAID", "")), List.of())));

        JsonNode r = chat("cust_900", "Can you waive my late fee?");

        assertThat(r.at("/customerSnapshot/accountTier").asText()).isEqualTo("Gold");
        assertThat(events(r)).anyMatch(e -> e.contains("CACHE_MISS -> WAREHOUSE_FALLBACK"))
                .contains("TOOL_CALL: evaluateWaiverRules -> APPROVED");
        verify(warehouse, times(1)).findProfile("cust_900");
    }

    @Test
    void accountStatusQuestionResolvesWithoutRetrievalOrViolation() throws Exception {
        JsonNode r = chat("cust_123", "What is my latest charge?");
        assertThat(r.at("/kpiImpact/deflectionStatus").asText()).isEqualTo("RESOLVED_AUTONOMOUSLY");
        assertThat(r.path("finalResponse").asText()).contains("LATE_FEE of $45.00");
        assertThat(events(r)).noneMatch(e -> e.startsWith("TOOL_CALL: searchPoliciesAndCases"))
                .doesNotContain("COMPLIANCE_VIOLATION_RECORDED");
    }

    @Test
    void unknownCustomerReturnsPartialNot500() throws Exception {
        when(warehouse.findProfile("ghost")).thenReturn(Optional.empty());
        JsonNode r = chat("ghost", "hello");
        assertThat(r.at("/kpiImpact/deflectionStatus").asText()).isEqualTo("PARTIAL");
        assertThat(r.path("customerSnapshot").isNull()).isTrue();
    }

    @Test
    void malformedRequestReturnsStructured400() throws Exception {
        mvc.perform(post("/api/agent/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"bad id; drop table\",\"message\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/api/agent/chat"))
                .andExpect(jsonPath("$.violations[?(@.field=='customerId')]").exists())
                .andExpect(jsonPath("$.violations[?(@.field=='message')]").exists());

        mvc.perform(post("/api/agent/chat").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void demoProfileEndpointReturnsCachedProfileOr404() throws Exception {
        mvc.perform(get("/api/demo/profile/cust_456")).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountTier").value("Enterprise"))
                .andExpect(jsonPath("$.activeTickets[0].ticketId").value("TKT-8841"));
        mvc.perform(get("/api/demo/profile/nobody")).andExpect(status().isNotFound());
    }

    @Test
    void seedEndpointsValidateInput() throws Exception {
        mvc.perform(post("/api/demo/customers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"c1\",\"fullName\":\"X\",\"accountTier\":\"Platinum\",\"tenureMonths\":1,\"mrr\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[?(@.field=='accountTier')]").exists());
        mvc.perform(post("/api/demo/tickets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"c1\",\"category\":\"T\",\"subject\":\"s\",\"status\":\"DONE\",\"slaHoursFromNow\":9999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[?(@.field=='status')]").exists())
                .andExpect(jsonPath("$.violations[?(@.field=='slaHoursFromNow')]").exists());
        mvc.perform(post("/api/demo/poison-pill").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"table\":\"orders\"}"))
                .andExpect(status().isBadRequest());
    }

    /** FR-6.3: Marcus reports the same ERR-504-GW problem; the agent recognises open ticket TKT-8841. */
    @Test
    void scenario4_existingTicketIsRecognisedAsFollowUp() throws Exception {
        JsonNode r = chat("cust_456", "Hi again, the gateway is still throwing ERR-504-GW, any update?");

        assertThat(events(r)).contains("TOOL_CALL: findMatchingOpenTicket -> EXISTING_TICKET TKT-8841 (ERROR_CODE)");
        String text = r.path("finalResponse").asText();
        assertThat(text).startsWith("You already have an open ticket for this: TKT-8841")
                .contains("PENDING_ENGINEERING").contains("no need to open a new one");
        assertThat(mapper.convertValue(r.at("/handoffCard/attemptedSteps"), List.class))
                .anyMatch(s -> s.toString().startsWith("Existing open ticket TKT-8841 (PENDING_ENGINEERING)"));
    }

    /** FR-6.3: a waiver request is a new request, so Elena's open billing ticket is not mentioned. */
    @Test
    void waiverRequestIsNotTreatedAsFollowUpOnBillingTicket() throws Exception {
        JsonNode r = chat("cust_123", "Can you waive the $45 late fee?");
        assertThat(r.path("finalResponse").asText()).doesNotContain("already have an open ticket");
        assertThat(events(r)).noneMatch(e -> e.startsWith("TOOL_CALL: findMatchingOpenTicket"));
    }

    @Test
    void unrelatedQuestionChecksTicketsButFindsNoMatch() throws Exception {
        JsonNode r = chat("cust_123", "What is my latest charge?");
        assertThat(events(r)).contains("TOOL_CALL: findMatchingOpenTicket -> NO_MATCH");
        assertThat(r.path("finalResponse").asText()).doesNotContain("already have an open ticket");
    }

    /** FR-1.5: a stub profile in Redis (no customer row yet) is treated as a miss, never as Standard tier. */
    @Test
    void stubProfileInRedisIsReadFromWarehouseNotTrusted() throws Exception {
        put(new CustomerProfile("cust_901", "", "Standard", 0, BigDecimal.ZERO, 0, 5L, List.of(),
                List.of(new TicketSummary("TKT-1", "BILLING", "Invoice question", "OPEN", ""))));
        when(warehouse.findProfile("cust_901")).thenReturn(Optional.of(new CustomerProfile("cust_901", "Gold Gail",
                "Gold", 30, BigDecimal.TEN, 0, 0L,
                List.of(new LedgerEntry("INV-901", "LATE_FEE", new BigDecimal("30.00"), "UNPAID", "")), List.of())));

        JsonNode r = chat("cust_901", "Can you waive my late fee?");

        assertThat(events(r)).anyMatch(e -> e.contains("CACHE_MISS -> WAREHOUSE_FALLBACK"))
                .contains("TOOL_CALL: evaluateWaiverRules -> APPROVED");
        assertThat(r.at("/customerSnapshot/accountTier").asText()).isEqualTo("Gold");
    }

    /** FR-3.10: Riley's "why is my bill so high?" runs the 5-step investigation and verifies every fact. */
    @Test
    void scenario5_multiStepBillingInvestigation() throws Exception {
        put(new CustomerProfile("cust_246", "Riley Morgan", "Enterprise", 30, new BigDecimal("1200.00"), 0, 404L,
                List.of(new LedgerEntry("INV-R-OVG", "OVERAGE", new BigDecimal("180.00"), "UNPAID", "API overage"),
                        new LedgerEntry("INV-R-LATE", "LATE_FEE", new BigDecimal("35.00"), "UNPAID", "Late fee"),
                        new LedgerEntry("INV-R-SUB", "SUBSCRIPTION", new BigDecimal("1200.00"), "PAID", "Monthly")),
                List.of(new TicketSummary("TKT-R1", "GATEWAY_OUTAGE", "P1 outage on EU gateway for 6 hours", "OPEN",
                        Instant.now().plus(1, ChronoUnit.DAYS).toString()))));

        JsonNode r = chat("cust_246", "Why is my bill so high this month?");

        assertThat(events(r)).contains("TOOL_CALL: checkWaiverEligibility -> APPROVED (not applied)",
                        "INVESTIGATE 5/5 VERIFIED: amounts, invoices, tickets and citations match tool results")
                .anyMatch(e -> e.startsWith("INVESTIGATE 1/5 billing: 2 unpaid charge(s) beyond subscription, total $215.00"))
                .anyMatch(e -> e.startsWith("INVESTIGATE 2/5 tickets: 1 open"))
                .anyMatch(e -> e.startsWith("INVESTIGATE 4/5 policy: SLA credit checked for TKT-R1"))
                .noneMatch(e -> e.startsWith("TOOL_CALL: evaluateWaiverRules"))
                .doesNotContain("COMPLIANCE_VIOLATION_RECORDED");
        String text = r.path("finalResponse").asText();
        assertThat(text).contains("OVERAGE $180.00 (INV-R-OVG)", "LATE_FEE $35.00 (INV-R-LATE)", "$215.00",
                "eligible for a one-time waiver", "TKT-R1");
        assertThat(mapper.convertValue(r.path("citations"), List.class))
                .contains("billing_waivers_policy.md#sec-4.2", "sla_credit_policy.md#sec-4.1");
        assertThat(r.at("/kpiImpact/deflectionStatus").asText()).isEqualTo("RESOLVED_AUTONOMOUSLY");
        verify(actionPublisher, never()).publish(eq("waiver"), eq("cust_246"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void waiverRequestStillUsesWaiverPathNotInvestigation() throws Exception {
        JsonNode r = chat("cust_123", "Why is my bill $45 higher, and can you waive it?");
        assertThat(events(r)).contains("TOOL_CALL: evaluateWaiverRules -> APPROVED")
                .noneMatch(e -> e.startsWith("INVESTIGATE"));
    }

    /** FR-6.5: a customer asks about their ticket; status, SLA and no needless escalation when within SLA. */
    @Test
    void scenario6_ticketStatusWithinSlaResolves() throws Exception {
        JsonNode r = chat("cust_123", "Hi, what's the status of my ticket?");
        assertThat(events(r)).contains("TICKET_STATUS: 1 ticket(s) open");
        assertThat(r.path("finalResponse").asText()).contains("TKT-7721", "\"Invoice review\"", "is OPEN", "within SLA");
        assertThat(r.at("/kpiImpact/deflectionStatus").asText()).isEqualTo("RESOLVED_AUTONOMOUSLY");
    }

    @Test
    void ticketStatusByIdPastSlaEscalates() throws Exception {
        JsonNode r = chat("cust_456", "Any news on TKT-8841?");
        assertThat(events(r)).contains("TICKET_STATUS: 1 ticket(s) matching TKT-8841");
        assertThat(r.path("finalResponse").asText()).contains("PENDING_ENGINEERING", "SLA breached 4 hours ago");
        assertThat(r.at("/kpiImpact/deflectionStatus").asText()).isEqualTo("ESCALATED");
        assertThat(r.at("/handoffCard/classifiedIntent").asText()).isEqualTo("TICKET_STATUS");
    }

    @Test
    void ticketStatusWithNoOpenTicketsSaysSo() throws Exception {
        JsonNode r = chat("cust_789", "Is there any update on my ticket?");
        assertThat(r.path("finalResponse").asText()).contains("no open support tickets");
    }

    /** FR-6.6 scenario 13a: an unhappy customer asks for a person after a denial; escalated in every mode. */
    @Test
    void scenario13_unhappyCustomerAsksForAPersonAfterDenial() throws Exception {
        JsonNode first = chatIn("conv-13a", "cust_789", "Can you waive my late fee?");
        assertThat(first.path("finalResponse").asText()).contains("cannot waive", "ask a supervisor");
        assertThat(first.at("/kpiImpact/deflectionStatus").asText()).isEqualTo("RESOLVED_AUTONOMOUSLY");

        JsonNode second = chatIn("conv-13a", "cust_789", "That doesn't help me. Can I please speak to a real person?");
        assertThat(events(second)).contains("CONVERSATION_HISTORY_LOADED (1 earlier exchange)");
        assertThat(second.at("/kpiImpact/deflectionStatus").asText()).isEqualTo("ESCALATED");
        assertThat(second.at("/handoffCard/detectedSentiment").asText()).isEqualTo("CUSTOMER_REQUESTED_HUMAN");
        assertThat(second.at("/handoffCard/classifiedIntent").asText()).isEqualTo("BILLING_WAIVER");
        assertThat(mapper.convertValue(second.at("/handoffCard/attemptedSteps"), List.class))
                .anyMatch(s -> s.toString().contains("Can you waive my late fee?"));
        assertThat(second.path("finalResponse").asText()).contains("Tier-2 specialist")
                .doesNotContain("Here is what I see on your account");
    }

    /** FR-6.6 scenario 13b: policy back-and-forth; a follow-up with no topic keeps the earlier one. */
    @Test
    void scenario13_policyFollowUpKeepsTheTopic() throws Exception {
        chatIn("conv-13b", "cust_789", "Can you waive my late fee?");

        JsonNode followUp = chatIn("conv-13b", "cust_789", "Why not? Can you make an exception?");
        assertThat(events(followUp)).contains("CONVERSATION_FOLLOW_UP: routed with the earlier topic");
        assertThat(events(followUp)).anyMatch(e -> e.startsWith("TOOL_CALL: evaluateWaiverRules"));
        assertThat(followUp.path("finalResponse").asText()).contains("Standard", "ask a supervisor");

        JsonNode yes = chatIn("conv-13b", "cust_789", "Yes please");
        assertThat(events(yes)).contains("CONVERSATION_FOLLOW_UP: customer accepted supervisor review");
        assertThat(yes.at("/kpiImpact/deflectionStatus").asText()).isEqualTo("ESCALATED");
        assertThat(yes.path("finalResponse").asText()).contains("asked a supervisor to review it");
    }

    /** A thank-you ends the turn politely: no repeat of the earlier answer, no tools, no hand-off, no AHT credit. */
    @Test
    void scenario13_thankYouIsAcknowledgedNotAnsweredAgain() throws Exception {
        chatIn("conv-13c", "cust_789", "Can you waive my late fee?");

        JsonNode thanks = chatIn("conv-13c", "cust_789", "Ok thanks, that's all");
        assertThat(thanks.path("finalResponse").asText()).isEqualTo("You're welcome. Is there anything else I can help you with today?");
        assertThat(events(thanks)).contains("CONVERSATION_CLOSING: acknowledged without tools or a model call")
                .noneMatch(e -> e.startsWith("TOOL_CALL"));
        assertThat(thanks.at("/kpiImpact/deflectionStatus").asText()).isEqualTo("RESOLVED_AUTONOMOUSLY");
        assertThat(thanks.at("/kpiImpact/estimatedAhtSavedMinutes").asDouble()).isZero();

        chatIn("conv-13c", "cust_789", "Can I please speak to a real person?");
        JsonNode after = chatIn("conv-13c", "cust_789", "Thank you!");
        assertThat(after.path("finalResponse").asText()).contains("Tier-2 specialist has your case");
    }

    /** Memory is keyed by customer: the same conversation id never leaks another customer's history. */
    @Test
    void conversationMemoryIsScopedToTheCustomer() throws Exception {
        chatIn("conv-shared", "cust_789", "Can you waive my late fee?");
        JsonNode other = chatIn("conv-shared", "cust_123", "Why not?");
        assertThat(events(other)).noneMatch(e -> e.startsWith("CONVERSATION_"));
        JsonNode noId = chat("cust_789", "Yes please");
        assertThat(events(noId)).noneMatch(e -> e.startsWith("CONVERSATION_"));
    }

    /** A first turn without a conversation id can be continued with the sessionId the response returns. */
    @Test
    void returnedSessionIdContinuesTheConversation() throws Exception {
        JsonNode first = chat("cust_789", "Can you waive my late fee?");
        String sessionId = first.path("sessionId").asText();
        assertThat(sessionId).startsWith("sess-");

        JsonNode second = chatIn(sessionId, "cust_789", "Why not? Can you make an exception?");
        assertThat(second.path("sessionId").asText()).isEqualTo(sessionId);
        assertThat(events(second)).contains("CONVERSATION_HISTORY_LOADED (1 earlier exchange)");
    }

    @Test
    void kpiReportAggregatesTurns() throws Exception {
        when(dltService.totalDepth()).thenReturn(2L);
        chat("cust_123", "Can you waive the $45 late fee?");
        chat("cust_456", "ERR-504-GW again on the gateway");

        mvc.perform(get("/api/agent/reports/kpis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSessions").isNumber())
                .andExpect(jsonPath("$.escalations").isNumber())
                .andExpect(jsonPath("$.dltMessageDepth").value(2))
                .andExpect(jsonPath("$.policyComplianceScore").isNumber());
    }
}
