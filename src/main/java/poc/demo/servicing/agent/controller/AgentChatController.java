package poc.demo.servicing.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import poc.demo.servicing.agent.memory.ConversationMemory;
import poc.demo.servicing.agent.memory.ConversationMemory.Exchange;
import poc.demo.servicing.agent.dlt.DltService;
import poc.demo.servicing.agent.guardrails.PiiSanitizer;
import poc.demo.servicing.agent.llm.LlmCircuitBreaker;
import poc.demo.servicing.agent.llm.SingleCallSynthesizer;
import poc.demo.servicing.agent.model.AgentTurnReport;
import poc.demo.servicing.agent.model.CustomerProfile;
import poc.demo.servicing.agent.prompt.CacheUsageRecorder;
import poc.demo.servicing.agent.replay.DeterministicReplayAgent;
import poc.demo.servicing.agent.telemetry.KpiMetricsService;
import poc.demo.servicing.agent.telemetry.TraceCollector;
import poc.demo.servicing.agent.telemetry.TurnCostEstimator;
import poc.demo.servicing.agent.telemetry.TurnState;
import poc.demo.servicing.agent.tools.CustomerContextTools;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern CITATION =
            Pattern.compile("\\[([A-Za-z0-9_.\\-]+(?:#[A-Za-z0-9_.\\-]+)?)]");
    private static final Pattern APPROVAL_CLAIM = Pattern.compile(
            "\\b(i have approved|i've approved|has been approved|is approved|you qualify|we have approved)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MONEY = Pattern.compile("\\$(\\d[\\d,]*(?:\\.\\d{1,2})?)");
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
    private final TurnCostEstimator costEstimator;
    private final LlmCircuitBreaker circuitBreaker;
    private final ConversationMemory memory;
    private final ObjectMapper mapper;
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
            TurnCostEstimator costEstimator,
            LlmCircuitBreaker circuitBreaker,
            ConversationMemory memory,
            ObjectMapper mapper,
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
        this.costEstimator = costEstimator;
        this.circuitBreaker = circuitBreaker;
        this.memory = memory;
        this.mapper = mapper;
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
        turnState.setCustomerMessage(sanitized);
        log.info("Turn started: planner={} messageChars={}", replayMode ? "replay" : "llm", sanitized.length());
        trace.record(replayMode ? "PLANNER: DETERMINISTIC_REPLAY"
                : singlePass ? "PLANNER: LLM_SINGLE_PASS" : "PLANNER: LLM_AGENTIC");

        AgentTurnReport.CustomerSnapshot snapshot = snapshot(request.customerId());

        // FR-6.6: every turn is saved under its session id (the client's conversationId, or the one minted for
        // this turn and returned as sessionId), so a client continues the chat by sending that id back.
        String conversationId = sessionId;
        List<Exchange> history = memory.history(request.customerId(), conversationId);
        if (!history.isEmpty()) {
            trace.record("CONVERSATION_HISTORY_LOADED (" + history.size() + " earlier exchange"
                    + (history.size() == 1 ? ")" : "s)"));
        }

        String response;
        boolean failed = false;
        // A thank-you or goodbye needs no tools or facts: answered in Java in every mode, without a model call.
        boolean closing = DeterministicReplayAgent.isClosing(sanitized);
        try {
            if (closing) {
                response = replayAgent.closing(history);
            } else if (replayMode) {
                response = replayAgent.respond(request.customerId(), sanitized, history);
            } else if (!singlePass && !circuitBreaker.allowRequest()) {
                // Provider is failing: skip the model instead of waiting for retries and timeouts.
                trace.record("LLM_CIRCUIT_OPEN: deterministic answer returned");
                response = replayAgent.respond(request.customerId(), sanitized, history);
            } else if (singlePass) {
                response = runSinglePass(request.customerId(), sessionId, sanitized, history);
            } else {
                response = runLlm(request.customerId(), sessionId, sanitized, history);
                response = verifyAgenticAnswer(request.customerId(), sanitized, response);
                String escalation = replayAgent.ensureEscalationChecked(request.customerId(), sanitized);
                if (escalation != null) {
                    response = response.strip() + " " + escalation;
                }
            }
        } catch (Exception e) {
            trace.record("AGENT_TURN_FAILED: " + e.getClass().getSimpleName());
            if (!replayMode && !singlePass) {
                // Agent planner mode: a model call failed or timed out. Java still has the answer.
                log.warn("Agentic turn failed ({}); returning the deterministic answer", e.toString());
                trace.record("AGENTIC_FALLBACK: deterministic answer returned");
                boolean escalatedByModel = turnState.isEscalated();
                response = replayAgent.respond(request.customerId(), sanitized, history);
                response = DeterministicReplayAgent.reconcileEscalation(response, escalatedByModel);
            } else {
                log.error("Agent turn failed for customerId={} session={}", request.customerId(), sessionId, e);
                response = FALLBACK_RESPONSE;
                failed = true;
            }
        }

        List<String> citations = new ArrayList<>(extractCitations(response));
        response = enforceGroundedness(response, citations);
        memory.append(request.customerId(), conversationId, history, sanitized, response);

        String status = failed ? "PARTIAL" : turnState.isEscalated() ? "ESCALATED" : "RESOLVED_AUTONOMOUSLY";
        // A closing message is not a resolved inquiry, so it saves no handling time.
        double ahtSaved = "RESOLVED_AUTONOMOUSLY".equals(status) && !closing ? ahtSavedMinutes : 0.0;
        switch (status) {
            case "ESCALATED" -> kpi.recordEscalation();
            case "RESOLVED_AUTONOMOUSLY" -> kpi.recordDeflection(ahtSaved);
            default -> { }
        }

        log.info("Turn finished: status={} latencyMs={} tools={} citations={} llmRoundTrips={} llmMs={}",
                status, trace.getTotalLatencyMs(), turnState.getToolsExecuted(), citations,
                turnState.getLlmRoundTrips(), turnState.getLlmMillis());
        costEstimator.logTurn(turnState);
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

    private String runLlm(String customerId, String sessionId, String sanitizedMessage, List<Exchange> history) {
        String prompt = "Customer ID: " + customerId + "\nSession ID: " + sessionId + "\n\n"
                + profileBlock(customerId) + ConversationMemory.asPromptBlock(history)
                + "Customer message: " + sanitizedMessage;
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
     * Agent planner: the profile loaded for this turn's snapshot goes in the prompt, so the model's first round
     * trip is not spent on getCustomerState. It is the same data that tool would return, read in this turn, and
     * it sits in the dynamic suffix, so the cached prefix is unchanged.
     */
    private String profileBlock(String customerId) {
        try {
            CustomerProfile p = turnState.cachedProfile(customerId);
            if (p == null) {
                p = customerContextTools.loadProfile(customerId);
            }
            trace.record("CONTEXT_PREFETCHED: customer profile in the prompt (getCustomerState round trip skipped)");
            return "Verified customer profile (loaded this turn; same data as getCustomerState):\n"
                    + mapper.writeValueAsString(p) + "\n\n";
        } catch (Exception e) {
            log.warn("Could not prefetch the customer profile; the model will call getCustomerState: {}", e.toString());
            return "";
        }
    }

    /**
     * Single-pass: the deterministic plan runs the tools and drafts a grounded reply, then one model call
     * rewrites it. The rewrite is discarded (draft kept) if it cites an id retrieval did not return or
     * claims an approval the rule engine did not give, so the model cannot change a decision.
     */
    private String runSinglePass(String customerId, String sessionId, String sanitizedMessage,
                                 List<Exchange> history) {
        String draft = replayAgent.respond(customerId, sanitizedMessage, history);
        trace.record("DETERMINISTIC_PLAN_EXECUTED");
        CustomerProfile profile = turnState.cachedProfile(customerId);
        String rewritten = synthesizer.rewrite(profile, sessionId, sanitizedMessage, draft, history);
        if (rewritten == null) {
            trace.record("LLM_SYNTHESIS_FALLBACK: deterministic draft returned");
            return draft;
        }
        Set<String> retrieved = turnState.getRetrievedDocIds();
        boolean badCitation = extractCitations(rewritten).stream().anyMatch(c -> !retrieved.contains(c));
        boolean droppedCitations = !extractCitations(draft).isEmpty() && extractCitations(rewritten).isEmpty();
        boolean badApproval = APPROVAL_CLAIM.matcher(rewritten).find()
                && !"APPROVED".equals(turnState.getLastWaiverReasonCode());
        boolean badAmount = amounts(rewritten).stream().anyMatch(a -> !amounts(draft).contains(a));
        if (badCitation || droppedCitations || badApproval || badAmount) {
            trace.record("LLM_SYNTHESIS_REJECTED (" + (badCitation ? "UNKNOWN_CITATION" : droppedCitations
                    ? "CITATIONS_DROPPED" : badApproval ? "UNBACKED_APPROVAL" : "UNVERIFIED_AMOUNT")
                    + "): deterministic draft returned");
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

    /** Dollar amounts in a reply, normalised (45 and 45.00 are equal); the rewrite may not add new ones. */
    static Set<BigDecimal> amounts(String text) {
        Set<BigDecimal> found = new HashSet<>();
        if (text != null) {
            Matcher m = MONEY.matcher(text);
            while (m.find()) {
                found.add(new BigDecimal(m.group(1).replace(",", "")).stripTrailingZeros());
            }
        }
        return found;
    }

    /**
     * FR-3.10 in agentic mode: the model chose the tools and wrote the answer, so Java checks the answer the
     * same way as the deterministic investigation. Every amount, invoice, ticket id and citation must come
     * from a tool result; otherwise the deterministic answer is returned instead.
     */
    private String verifyAgenticAnswer(String customerId, String message, String response) {
        if (!turnState.getToolsExecuted().contains("checkWaiverEligibility")) {
            return response; // not an investigation turn; citation and approval checks still run in enforceGroundedness
        }
        CustomerProfile profile = turnState.cachedProfile(customerId);
        if (profile == null) {
            profile = customerContextTools.loadProfile(customerId);
        }
        List<String> failures = replayAgent.verify(response, profile);
        if (failures.isEmpty()) {
            trace.record("AGENTIC_VERIFIED: amounts, invoices, tickets and citations match tool results");
            return response;
        }
        trace.record("AGENTIC_VERIFY_FAILED " + failures + ": deterministic answer returned");
        log.warn("Agentic answer failed verification {}; returning the deterministic answer", failures);
        return replayAgent.respond(customerId, message);
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
