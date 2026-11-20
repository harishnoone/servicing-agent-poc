package io.github.harishnoone.servicingagent.tools;

import io.github.harishnoone.servicingagent.admin.AuditService;
import io.github.harishnoone.servicingagent.model.CustomerProfile;
import io.github.harishnoone.servicingagent.model.WaiverEvaluationResult;
import io.github.harishnoone.servicingagent.telemetry.TraceCollector;
import io.github.harishnoone.servicingagent.telemetry.TurnState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spring AI tool that evaluates late-fee waiver eligibility using pure Java rules.
 *
 * <p>FR-5.1: thresholds are externalised to {@code application.yml}.
 * FR-5.2: every rejection carries an auditable reason code.
 * FR-5.3: the LLM never makes the approval decision; this class does.
 */
@Component
public class DeterministicRulesTools {

    private static final Logger log = LoggerFactory.getLogger(DeterministicRulesTools.class);

    static final String POLICY_CITATION = "billing_waivers_policy.md#sec-4.2";

    private final BigDecimal           maxFeeAmount;
    private final int                  minTenureMonths;
    private final Set<String>          eligibleTiers;
    private final int                  maxPriorWaivers;
    private final CustomerContextTools customerContextTools;
    private final TraceCollector       traceCollector;
    private final TurnState            turnState;
    private final AuditService         auditService;

    public DeterministicRulesTools(
            @Value("${agent.rules.waiver.max-fee-amount}")       BigDecimal maxFeeAmount,
            @Value("${agent.rules.waiver.min-tenure-months}")    int minTenureMonths,
            @Value("${agent.rules.waiver.eligible-tiers}")       String eligibleTiersStr,
            @Value("${agent.rules.waiver.max-waivers-per-year}") int maxPriorWaivers,
            CustomerContextTools customerContextTools,
            TraceCollector traceCollector,
            TurnState turnState,
            AuditService auditService) {
        this.maxFeeAmount         = maxFeeAmount.setScale(2, java.math.RoundingMode.HALF_UP);
        this.minTenureMonths      = minTenureMonths;
        this.eligibleTiers        = Arrays.stream(eligibleTiersStr.split(","))
                .map(String::trim).collect(Collectors.toSet());
        this.maxPriorWaivers      = maxPriorWaivers;
        this.customerContextTools = customerContextTools;
        this.traceCollector       = traceCollector;
        this.turnState            = turnState;
        this.auditService         = auditService;
    }

    /**
     * Evaluates whether the customer may receive a waiver of the given amount.
     *
     * <p>Rules (billing_waivers_policy.md section 4.2), evaluated in order:
     * fee ceiling, tier, tenure, prior waivers in the rolling 12 months. A customer
     * may have at most {@code max-waivers-per-year} prior waivers (policy: 0, so the
     * first waiver is allowed and a second is not).
     */
    @Tool(description = "Deterministically evaluate whether a customer is eligible for a late fee "
            + "waiver based on account tier, tenure, fee amount, and prior waivers. Uses pure Java "
            + "rules, never LLM judgment. Always use this tool for waiver decisions; never decide "
            + "eligibility yourself.")
    public WaiverEvaluationResult evaluateWaiverRules(
            @ToolParam(description = "Customer account ID, e.g. cust_123") String customerId,
            @ToolParam(description = "Late fee amount the customer wants waived, in dollars") BigDecimal requestedWaiverAmount) {

        long start = System.currentTimeMillis();
        CustomerProfile profile = customerContextTools.loadProfile(customerId);
        WaiverEvaluationResult result = evaluate(profile, requestedWaiverAmount);
        if (result.eligible()) {
            emitApprovalAuditEvent(customerId, requestedWaiverAmount, result);
        }

        turnState.recordTool("evaluateWaiverRules");
        turnState.recordWaiverOutcome(result.reasonCode());
        traceCollector.record("TOOL_CALL: evaluateWaiverRules -> " + result.reasonCode(),
                System.currentTimeMillis() - start);
        return result;
    }

    /** Approval is emitted as an auditable action event; failure to audit must not break the turn. */
    private void emitApprovalAuditEvent(String customerId, BigDecimal amount, WaiverEvaluationResult result) {
        try {
            auditService.append("WAIVER_APPROVED_AUTONOMOUS", "agent:tier1", customerId, null, amount, result.explanation());
        } catch (Exception e) {
            log.warn("Could not write approval audit event for {}: {}", customerId, e.getMessage());
        }
    }

    /** Pure rule evaluation, separated from tracing so it is trivially unit-testable. */
    WaiverEvaluationResult evaluate(CustomerProfile profile, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return deny("INVALID_AMOUNT", "Requested waiver amount must be a positive number.");
        }
        if (amount.compareTo(maxFeeAmount) > 0) {
            return deny("FEE_TOO_HIGH", String.format(
                    "Requested waiver amount $%s exceeds the maximum allowed $%s per %s.",
                    money(amount), maxFeeAmount, POLICY_CITATION));
        }
        if (!eligibleTiers.contains(profile.accountTier())) {
            return deny("TIER_INELIGIBLE", String.format(
                    "Account tier '%s' is not in the eligible tier set %s per %s.",
                    profile.accountTier(), eligibleTiers, POLICY_CITATION));
        }
        if (profile.tenureMonths() < minTenureMonths) {
            return deny("TENURE_TOO_SHORT", String.format(
                    "Customer tenure %d months is below the required minimum of %d months per %s.",
                    profile.tenureMonths(), minTenureMonths, POLICY_CITATION));
        }
        if (profile.waiversUsed12m() > maxPriorWaivers) {
            return deny("WAIVER_LIMIT_REACHED", String.format(
                    "Customer has already used %d waiver(s) in the last 12 months "
                            + "(maximum prior waivers allowed: %d) per %s.",
                    profile.waiversUsed12m(), maxPriorWaivers, POLICY_CITATION));
        }
        return new WaiverEvaluationResult(true, "APPROVED", POLICY_CITATION, String.format(
                "Customer meets all eligibility criteria: tier=%s, tenure=%d months, "
                        + "waivers used=%d, requested amount=$%s. Approved per %s.",
                profile.accountTier(), profile.tenureMonths(),
                profile.waiversUsed12m(), money(amount), POLICY_CITATION));
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private WaiverEvaluationResult deny(String reasonCode, String explanation) {
        return new WaiverEvaluationResult(false, reasonCode, POLICY_CITATION, explanation);
    }
}
