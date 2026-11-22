package unit.tests.servicingagent;

import unit.tests.servicingagent.admin.AdminOverrideService;
import unit.tests.servicingagent.dlt.DltService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-8.1/FR-8.2: scope-based RBAC when an issuer is configured, and the audit subject comes from the JWT. */
@SpringBootTest(properties = {
        "OAUTH2_ISSUER_URI=https://issuer.example.test",
        "agent.llm.mode=replay",
        "agent.kafka.retry-topics.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "agent.debezium.connect-url="
})
@AutoConfigureMockMvc
class SecurityJwtModeTest {

    private static final String OVERRIDE = """
            {"customerId":"cust_456","invoiceId":"INV-2025-456-001","amount":75.00,
             "justification":"Customer impacted by ERR-504-GW outage"}""";

    @Autowired MockMvc mvc;
    @MockitoBean AdminOverrideService overrideService;
    @MockitoBean DltService dltService;

    @Test
    void adminRequiresToken() throws Exception {
        mvc.perform(post("/api/admin/overrides/waiver").contentType(MediaType.APPLICATION_JSON).content(OVERRIDE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminRejectsWrongScope() throws Exception {
        mvc.perform(post("/api/admin/overrides/waiver").contentType(MediaType.APPLICATION_JSON).content(OVERRIDE)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_customer.chat"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void supervisorCanOverrideAndSubjectIsAudited() throws Exception {
        when(overrideService.applyWaiverOverride(any(), any(), any(), any(), any())).thenReturn(
                new AdminOverrideService.OverrideResult(1L, "cust_456", "INV-2025-456-001", new BigDecimal("75.00"),
                        "supervisor-jane", Instant.now(), "APPLIED"));

        mvc.perform(post("/api/admin/overrides/waiver").contentType(MediaType.APPLICATION_JSON).content(OVERRIDE)
                        .with(jwt().jwt(j -> j.subject("supervisor-jane"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_servicing.supervisor"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));

        verify(overrideService).applyWaiverOverride(eq("supervisor-jane"), eq("cust_456"), eq("INV-2025-456-001"),
                argThat(a -> a.compareTo(new BigDecimal("75")) == 0), contains("ERR-504-GW"));
    }

    @Test
    void replayRejectsNonDltTopicName() throws Exception {
        when(dltService.replay(eq("orders"), anyInt())).thenThrow(new IllegalArgumentException("Unknown dead-letter topic"));
        mvc.perform(post("/api/admin/kafka/dlt/replay").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"orders\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_servicing.supervisor"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chatRequiresChatScope() throws Exception {
        mvc.perform(get("/api/agent/reports/kpis")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/agent/reports/kpis")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_customer.chat"))))
                .andExpect(status().isOk());
    }
}
