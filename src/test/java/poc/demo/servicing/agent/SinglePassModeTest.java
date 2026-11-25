package poc.demo.servicing.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import poc.demo.servicing.agent.actions.ActionPublisher;
import poc.demo.servicing.agent.admin.AuditService;
import poc.demo.servicing.agent.cache.WarehouseFallbackRepository;
import poc.demo.servicing.agent.dlt.DltService;
import poc.demo.servicing.agent.llm.SingleCallSynthesizer;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LLM single-pass mode: Java runs the tool plan, the model is called once (mocked here) to write the
 * reply, and the reply is rejected in favour of the deterministic draft if it breaks a guardrail.
 */
@SpringBootTest(properties = {
        "agent.llm.mode=llm",
        "agent.llm.orchestration=single-pass",
        "agent.kafka.retry-topics.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "agent.debezium.connect-url="
})
@AutoConfigureMockMvc
class SinglePassModeTest {

    static final FakeRedis REDIS = new FakeRedis();

    @TestConfiguration
    static class RedisConfig {
        @Bean StringRedisTemplate stringRedisTemplate() { return REDIS.template; }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean AuditService auditService;
    @MockitoBean ActionPublisher actionPublisher;
    @MockitoBean WarehouseFallbackRepository warehouse;
    @MockitoBean DltService dltService;
    @MockitoBean SingleCallSynthesizer synthesizer;

    @BeforeEach
    void seed() throws Exception {
        REDIS.store.clear();
        put(new CustomerProfile("cust_123", "Elena Vance", "Gold", 36, new BigDecimal("499.00"), 0, 101L,
                List.of(new LedgerEntry("INV-A", "LATE_FEE", new BigDecimal("45.00"), "UNPAID", "Late fee")), List.of()));
        put(new CustomerProfile("cust_456", "Marcus Sterling", "Enterprise", 48, new BigDecimal("2400.00"), 1, 202L,
                List.of(), List.of(new TicketSummary("TKT-8841", "GATEWAY_OUTAGE", "ERR-504-GW",
                        "PENDING_ENGINEERING", Instant.now().minus(4, ChronoUnit.HOURS).toString()))));
    }

    private void put(CustomerProfile p) throws Exception {
        REDIS.store.put("customer:profile:" + p.customerId(), mapper.writeValueAsString(p));
    }

    private JsonNode chat(String customerId, String message) throws Exception {
        String body = mapper.writeValueAsString(Map.of("customerId", customerId, "message", message));
        return mapper.readTree(mvc.perform(post("/api/agent/chat").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static List<String> events(JsonNode r) {
        return r.path("agentReasoningTrace").findValuesAsText("event");
    }

    @Test
    void groundedRewriteIsReturnedAndModelIsCalledOnce() throws Exception {
        when(synthesizer.rewrite(any(), anyString(), anyString(), anyString(), anyList())).thenReturn(
                "Good news Elena, your $45.00 late fee is waived under [billing_waivers_policy.md#sec-4.2].");
        JsonNode r = chat("cust_123", "Can you waive my $45 late fee?");
        assertThat(r.path("finalResponse").asText()).startsWith("Good news Elena");
        assertThat(r.at("/kpiImpact/deflectionStatus").asText()).isEqualTo("RESOLVED_AUTONOMOUSLY");
        assertThat(events(r)).contains("PLANNER: LLM_SINGLE_PASS", "DETERMINISTIC_PLAN_EXECUTED",
                "LLM_SYNTHESIS_ACCEPTED", "TOOL_CALL: evaluateWaiverRules -> APPROVED");
        verify(synthesizer, times(1)).rewrite(any(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void providerFailureFallsBackToDeterministicDraftNotPartial() throws Exception {
        when(synthesizer.rewrite(any(), anyString(), anyString(), anyString(), anyList())).thenReturn(null);
        JsonNode r = chat("cust_456", "My gateway is throwing ERR-504-GW, what should I do?");
        assertThat(r.at("/kpiImpact/deflectionStatus").asText()).isEqualTo("ESCALATED");
        assertThat(r.path("handoffCard").path("detectedSentiment").asText()).isEqualTo("SLA_BREACH");
        assertThat(r.path("finalResponse").asText()).contains("[troubleshooting_guide.md#sec-1.1]");
        assertThat(events(r)).contains("LLM_SYNTHESIS_FALLBACK: deterministic draft returned");
    }

    @Test
    void rewriteWithAmountNotInVerifiedDraftIsRejected() throws Exception {
        when(synthesizer.rewrite(any(), anyString(), anyString(), anyString(), anyList()))
                .thenReturn("Your $45.00 late fee is waived under [billing_waivers_policy.md#sec-4.2], plus a $20 goodwill credit.");
        JsonNode r = chat("cust_123", "Can you waive my $45 late fee?");
        assertThat(r.path("finalResponse").asText()).doesNotContain("$20");
        assertThat(events(r)).anyMatch(e -> e.startsWith("LLM_SYNTHESIS_REJECTED (UNVERIFIED_AMOUNT)"));
    }

    @Test
    void rewriteWithUnknownCitationIsRejected() throws Exception {
        when(synthesizer.rewrite(any(), anyString(), anyString(), anyString(), anyList()))
                .thenReturn("Per [made_up_policy.md#sec-9] you get a full refund.");
        JsonNode r = chat("cust_123", "Can you waive my $45 late fee?");
        assertThat(r.path("finalResponse").asText()).doesNotContain("made_up_policy").contains("[billing_waivers_policy.md#sec-4.2]");
        assertThat(events(r)).anyMatch(e -> e.startsWith("LLM_SYNTHESIS_REJECTED (UNKNOWN_CITATION)"));
    }

    @Test
    void rewriteClaimingUnbackedApprovalIsRejected() throws Exception {
        when(synthesizer.rewrite(any(), anyString(), anyString(), anyString(), anyList()))
                .thenReturn("Your $60 fee has been approved for a waiver [billing_waivers_policy.md#sec-4.2].");
        JsonNode r = chat("cust_123", "Can you waive my $60 late fee?");
        assertThat(events(r)).contains("TOOL_CALL: evaluateWaiverRules -> FEE_TOO_HIGH")
                .anyMatch(e -> e.startsWith("LLM_SYNTHESIS_REJECTED (UNBACKED_APPROVAL)"));
        assertThat(r.path("finalResponse").asText()).contains("cannot waive");
    }
}