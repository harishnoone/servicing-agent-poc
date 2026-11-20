package io.github.harishnoone.servicingagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.harishnoone.servicingagent.admin.AuditService;
import io.github.harishnoone.servicingagent.cache.WarehouseFallbackRepository;
import io.github.harishnoone.servicingagent.dlt.DltService;
import io.github.harishnoone.servicingagent.model.CustomerProfile;
import io.github.harishnoone.servicingagent.model.CustomerProfile.LedgerEntry;
import io.github.harishnoone.servicingagent.model.CustomerProfile.TicketSummary;
import io.github.harishnoone.servicingagent.support.FakeRedis;
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
        String body = mapper.writeValueAsString(java.util.Map.of("customerId", customerId, "message", message));
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
        verify(auditService).append(eq("WAIVER_APPROVED_AUTONOMOUS"), eq("agent:tier1"), eq("cust_123"),
                isNull(), argThat(a -> a.compareTo(new BigDecimal("45")) == 0), anyString());
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
        verify(auditService, never()).append(eq("WAIVER_APPROVED_AUTONOMOUS"), any(), eq("cust_789"), any(), any(), any());
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
