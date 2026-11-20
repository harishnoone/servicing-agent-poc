package io.github.harishnoone.servicingagent.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.harishnoone.servicingagent.admin.AuditService;
import io.github.harishnoone.servicingagent.cache.WarehouseFallbackRepository;
import io.github.harishnoone.servicingagent.dlt.DltService;
import io.github.harishnoone.servicingagent.model.CustomerProfile;
import io.github.harishnoone.servicingagent.model.CustomerProfile.LedgerEntry;
import io.github.harishnoone.servicingagent.support.FakeRedis;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Live check against a real LLM (OpenAI, or Gemini via its OpenAI-compatible endpoint; needs OPENAI_API_KEY,\n * skipped otherwise). Turns are spaced LIVE_TURN_DELAY_MS apart (default 8000) to stay under provider rate limits. Runs several
 * turns for one customer and prints per-turn token usage so cache hits can be read off.
 * Redis is an in-memory fake; nothing else needs Docker.
 */
abstract class LiveCachingBase {

    static final FakeRedis REDIS = new FakeRedis();

    @TestConfiguration
    static class RedisConfig {
        @Bean StringRedisTemplate stringRedisTemplate() { return REDIS.template; }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean AuditService auditService;
    @MockitoBean WarehouseFallbackRepository warehouse;
    @MockitoBean DltService dltService;

    abstract String label();

    @Test
    void multiTurnUsage() throws Exception {
        REDIS.store.put("customer:profile:cust_123", mapper.writeValueAsString(new CustomerProfile(
                "cust_123", "Elena Vance", "Gold", 36, new BigDecimal("499.00"), 0, 101L,
                List.of(new LedgerEntry("INV-A", "LATE_FEE", new BigDecimal("45.00"), "UNPAID", "Late fee")),
                List.of())));
        String[] messages = {
                "Why is my bill $45 higher, and can you waive it?",
                "What is the maximum late fee you can waive for a customer like me?",
                "How many waivers can I get per year?",
                "Thanks. Can you summarise what you decided?"};
        System.out.println("=== LIVE " + label() + " ===");
        int turns = Math.min(messages.length, Integer.parseInt(System.getenv().getOrDefault("LIVE_TURNS", "4")));
        for (int i = 0; i < turns; i++) {
            long t0 = System.currentTimeMillis();
            String body = mapper.writeValueAsString(Map.of("customerId", "cust_123", "message", messages[i]));
            JsonNode r = mapper.readTree(mvc.perform(post("/api/agent/chat").contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andReturn().getResponse().getContentAsString());
            String usage = String.join(" | ", r.path("agentReasoningTrace").findValuesAsText("event").stream()
                    .filter(e -> e.startsWith("LLM_USAGE") || e.startsWith("AGENT_TURN_FAILED") || e.startsWith("TOOL_CALL")).toList());
            System.out.println("TURN " + (i + 1) + " wall=" + (System.currentTimeMillis() - t0) + "ms status="
                    + r.path("kpiImpact").path("deflectionStatus").asText() + " " + usage);
            System.out.println("REPLY " + (i + 1) + ": " + r.path("finalResponse").asText().replace("\n", " "));
            Thread.sleep(Long.parseLong(System.getenv().getOrDefault("LIVE_TURN_DELAY_MS", "8000")));
        }
        System.out.println("KPI " + mvc.perform(get("/api/agent/reports/kpis")).andReturn().getResponse().getContentAsString());
    }
}