package unit.tests.servicingagent.controller;

import unit.tests.servicingagent.dlt.DltService;
import unit.tests.servicingagent.guardrails.PiiSanitizer;
import unit.tests.servicingagent.llm.SingleCallSynthesizer;
import unit.tests.servicingagent.model.AgentTurnReport;
import unit.tests.servicingagent.model.CustomerProfile;
import unit.tests.servicingagent.prompt.CacheUsageRecorder;
import unit.tests.servicingagent.replay.DeterministicReplayAgent;
import unit.tests.servicingagent.telemetry.KpiMetricsService;
import unit.tests.servicingagent.telemetry.TraceCollector;
import unit.tests.servicingagent.telemetry.TurnState;
import unit.tests.servicingagent.tools.CustomerContextTools;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;

/**
 * Agent entry point (FR-2, FR-3, FR-7).
 *
 * <p>One call runs one agent turn and returns an {@link AgentTurnReport}. Escalation,
 * citations and retrieval facts come from {@link TurnState}, which the tools populate,
 * so the report never depends on parsing the model's prose for decisions.
 */
@RestController
@RequestMapping("/api/agent")
@Tag(name = "Agent")
public class AgentChatController {

    private static final Logger log = LoggerFactory.getLogger(AgentChatController.class);

    private static final java.util.regex.Pattern CITATION =
            java.util.regex.Pattern.compile("\\[([A-Za-z0-9_.\\-]+(?:#[A-Za-z0-9_.\\-]+)?)]");
    private static final java.util.regex.Pattern APPROVAL_CLAIM = java.util.regex.Pattern.compile(
            "\\b(i have approved|i've approved|has been approved|is approved|you qualify|we have approved)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final String FALLBACK_RESPONSE =
            "I was not able to complete this request automatically. A human agent will follow up with you.";

    private final ChatClient chatClient;
    private final DeterministicReplayAgent replayAgent;
    private final PiiSanitizer piiSanitizer;
    private final CustomerContextTools customerContextTools;
    private final KpiMetricsService kpi;
    private final DltService dltService;
    private final TraceCollector trace;
    private final TurnState turnState;
    private final CacheUsageRecorder cacheUsage;
    private final SingleCallSynthesizer synthesizer;
    private final boolean replayMode;
    private final boolean singlePass;
    private final double ahtSavedMinutes;
    private final double minRetrievalConfidence;

    public AgentChatController(
            ChatClient chatClient,
            DeterministicReplayAgent replayAgent,
            PiiSanitizer piiSanitizer,
            CustomerContextTools customerContextTools,
            KpiMetricsService kpi,
            DltService dltService,
            TraceCollector trace,
            TurnState turnState,
            CacheUsageRecorder cacheUsage,
            SingleCallSynthesizer synthesizer,
            @Value("${agent.llm.orchestration:single-pass}") String orchestration,
            @Value("${agent.llm.mode:auto}") String llmMode,
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${agent.kpi.aht-saved-minutes:6.5}") double ahtSavedMinutes,
            @Value("${agent.escalation.min-retrieval-confidence:0.72}") double minRetrievalConfidence) {
        this.chatClient = chatClient;
        this.replayAgent = replayAgent;
        this.piiSanitizer = piiSanitizer;
        this.customerContextTools = customerContextTools;
        this.kpi = kpi;
        this.dltService = dltService;
        this.trace = trace;
        this.turnState = turnState;
        this.cacheUsage = cacheUsage;
        this.synthesizer = synthesizer;
        this.singlePass = !"agentic".equalsIgnoreCase(orchestration);
        this.replayMode = resolveReplay(llmMode, apiKey);
        this.ahtSavedMinutes = ahtSavedMinutes;
        this.minRetrievalConfidence = minRetrievalConfidence;
        log.info("Agent planner mode: {}", replayMode ? "DETERMINISTIC_REPLAY (offline)"
                : singlePass ? "LLM single-pass (Java tool plan + one model call)" : "LLM agentic (model calls tools)");
    }

    static boolean resolveReplay(String mode, String apiKey) {
        if ("replay".equalsIgnoreCase(mode)) return true;
        if ("llm".equalsIgnoreCase(mode)) return false;
        return apiKey == null || apiKey.isBlank() || apiKey.startsWith("placeholder")
                || apiKey.startsWith("your-") || apiKey.contains("replace");
    }

    /**
     * @param customerId     account id, letters/digits/underscore/hyphen, at most 32 chars
     * @param message        customer message, at most 2000 chars
     * @param conversationId optional session id for grouping turns
     */
    public record ChatRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_\\-]{1,32}$",
                    message = "must be 1-32 characters: letters, digits, underscore or hyphen") String customerId,
            @NotBlank @Size(max = 2000, message = "must be at most 2000 characters") String message,
            @Pattern(regexp = "^[A-Za-z0-9_\\-]{0,64}$", message = "must be at most 64 characters: letters, digits, underscore or hyphen") String conversationId
    ) {}

    @PostMapping("/chat")
    @Operation(summary = "Run one agent turn",
            description = "Returns the reply, inline [doc_id] citations, the ordered reasoning trace with per-tool "
                    + "latency, KPI impact and, when escalated, the Tier-2 handoff card.")
    public ResponseEntity<AgentTurnReport> chat(@RequestBody @Valid ChatRequest request) {
        kpi.recordSession();
        String sessionId = request.conversationId() != null && !request.conversationId().isBlank()
                ? request.conversationId() : "sess-" + UUID.randomUUID().toString().substring(0, 8);

        MDC.put("sessionId", sessionId);
        MDC.put("customerId", request.customerId());
        try {
            return runTurn(request, sessionId);
        } finally {
            MDC.remove("sessionId");
            MDC.remove("customerId");
        }
    }

    private ResponseEntity<AgentTurnReport> runTurn(ChatRequest request, String sessionId) {
        String sanitized = piiSanitizer.sanitize(request.message());
        trace.record("PII_SANITIZATION_PASSED");
        log.info("Turn started: planner={} messageChars={}", replayMode ? "replay" : "llm", sanitized.length());
        trace.record(replayMode ? "PLANNER: DETERMINISTIC_REPLAY"
                : singlePass ? "PLANNER: LLM_SINGLE_PASS" : "PLANNER: LLM_AGENTIC");

        AgentTurnReport.CustomerSnapshot snapshot = snapshot(request.customerId());

        String response;
        boolean failed = false;
        try {
            if (replayMode) {
                response = replayAgent.respond(request.customerId(), sanitized);
            } else if (singlePass) {
                response = runSinglePass(request.customerId(), sessionId, sanitized);
            } else {
                response = runLlm(request.customerId(), sessionId, sanitized);
            }
        } catch (Exception e) {
            log.error("Agent turn failed for customerId={} session={}", request.customerId(), sessionId, e);
            trace.record("AGENT_TURN_FAILED: " + e.getClass().getSimpleName());
            response = FALLBACK_RESPONSE;
            failed = true;
        }

        List<String> citations = new ArrayList<>(extractCitations(response));
        response = enforceGroundedness(response, citations);

        String status = failed ? "PARTIAL" : turnState.isEscalated() ? "ESCALATED" : "RESOLVED_AUTONOMOUSLY";
        double ahtSaved = "RESOLVED_AUTONOMOUSLY".equals(status) ? ahtSavedMinutes : 0.0;
        switch (status) {
            case "ESCALATED" -> kpi.recordEscalation();
            case "RESOLVED_AUTONOMOUSLY" -> kpi.recordDeflection(ahtSaved);
            default -> { }
        }

        log.info("Turn finished: status={} latencyMs={} tools={} citations={} llmRoundTrips={} llmMs={}",
                status, trace.getTotalLatencyMs(), turnState.getToolsExecuted(), citations,
                turnState.getLlmRoundTrips(), turnState.getLlmMillis());
        AgentTurnReport report = new AgentTurnReport(
                sessionId, request.customerId(), snapshot, trace.getSteps(), response, citations,
                new AgentTurnReport.KpiImpact(status, ahtSaved, trace.getTotalLatencyMs()),
                turnState.getHandoffCard());
        return ResponseEntity.ok(report);
    }

    @GetMapping("/reports/kpis")
    @Operation(summary = "Executive KPI report",
            description = "Session count, autonomous deflection rate, AHT minutes saved, compliance score, DLT depth.")
    public ResponseEntity<KpiMetricsService.KpiReport> kpis() {
        kpi.setDltDepth(dltService.totalDepth());
        return ResponseEntity.ok(kpi.getReport());
    }

    // -------------------------------------------------------------------------

    private String runLlm(String customerId, String sessionId, String sanitizedMessage) {
        String prompt = "Customer ID: " + customerId + "\nSession ID: " + sessionId
                + "\n\nCustomer message: " + sanitizedMessage;
        // Passed as a template parameter so braces in the customer text are not parsed as placeholders.
        // Customer id, session id and message are the dynamic suffix; the system message is the cached prefix.
        ChatResponse chatResponse = chatClient.prompt()
                .user(u -> u.text("{input}").param("input", prompt))
                .call()
                .chatResponse();
        trace.record("LLM_RESPONSE_RECEIVED");
        cacheUsage.record(chatResponse);
        String content = chatResponse == null || chatResponse.getResult() == null
                ? null : chatResponse.getResult().getOutput().getText();
        return content == null ? "" : content;
    }

    /**
     * Single-pass: the deterministic plan runs the tools and drafts a grounded reply, then one model call
     * rewrites it. The rewrite is discarded (draft kept) if it cites an id retrieval did not return or
     * claims an approval the rule engine did not give, so the model cannot change a decision.
     */
    private String runSinglePass(String customerId, String sessionId, String sanitizedMessage) {
        String draft = replayAgent.respond(customerId, sanitizedMessage);
        trace.record("DETERMINISTIC_PLAN_EXECUTED");
        CustomerProfile profile = turnState.cachedProfile(customerId);
        String rewritten = synthesizer.rewrite(profile, sessionId, sanitizedMessage, draft);
        if (rewritten == null) {
            trace.record("LLM_SYNTHESIS_FALLBACK: deterministic draft returned");
            return draft;
        }
        Set<String> retrieved = turnState.getRetrievedDocIds();
        boolean badCitation = extractCitations(rewritten).stream().anyMatch(c -> !retrieved.contains(c));
        boolean droppedCitations = !extractCitations(draft).isEmpty() && extractCitations(rewritten).isEmpty();
        boolean badApproval = APPROVAL_CLAIM.matcher(rewritten).find()
                && !"APPROVED".equals(turnState.getLastWaiverReasonCode());
        if (badCitation || droppedCitations || badApproval) {
            trace.record("LLM_SYNTHESIS_REJECTED (" + (badCitation ? "UNKNOWN_CITATION" : droppedCitations
                    ? "CITATIONS_DROPPED" : "UNBACKED_APPROVAL") + "): deterministic draft returned");
            log.warn("Single-pass rewrite rejected; returning the deterministic draft. Rejected text: {}",
                    rewritten.length() > 400 ? rewritten.substring(0, 400) + "..." : rewritten);
            return draft;
        }
        trace.record("LLM_SYNTHESIS_ACCEPTED");
        return rewritten;
    }

    private AgentTurnReport.CustomerSnapshot snapshot(String customerId) {
        try {
            CustomerProfile p = customerContextTools.loadProfile(customerId);
            trace.record("CUSTOMER_SNAPSHOT_LOADED");
            return new AgentTurnReport.CustomerSnapshot(p.accountTier(), p.tenureMonths(), p.lastAppliedLsn());
        } catch (Exception e) {
            trace.record("CUSTOMER_SNAPSHOT_UNAVAILABLE");
            return null;
        }
    }

    static Set<String> extractCitations(String response) {
        Set<String> found = new LinkedHashSet<>();
        if (response != null) {
            Matcher m = CITATION.matcher(response);
            while (m.find()) {
                found.add(m.group(1));
            }
        }
        return found;
    }

    /**
     * Mandatory-citation and groundedness checks. A cited id the tools never returned, a policy
     * answer without any citation, or an approval claim without an APPROVED rule result counts
     * as a compliance violation. Missing citations are repaired from the retrieved documents.
     */
    private String enforceGroundedness(String response, List<String> citations) {
        Set<String> retrieved = turnState.getRetrievedDocIds();
        boolean violation = false;

        for (String c : citations) {
            if (!retrieved.contains(c)) {
                violation = true;
                log.warn("Ungrounded citation [{}] not returned by any retrieval", c);
            }
        }
        citations.removeIf(c -> !retrieved.contains(c));

        Double confidence = turnState.getLowestRetrievalConfidence();
        boolean confidentRetrieval = confidence != null && confidence >= minRetrievalConfidence;
        if (confidentRetrieval && citations.isEmpty() && !retrieved.isEmpty()) {
            violation = true;
            String first = retrieved.iterator().next();
            citations.add(first);
            response = response + " Source: [" + first + "]";
        }
        if (APPROVAL_CLAIM.matcher(response).find() && !"APPROVED".equals(turnState.getLastWaiverReasonCode())) {
            violation = true;
            log.warn("Response claims an approval without an APPROVED rule result");
        }
        if (violation) {
            kpi.recordComplianceViolation();
            trace.record("COMPLIANCE_VIOLATION_RECORDED");
        }
        return response;
    }
}
