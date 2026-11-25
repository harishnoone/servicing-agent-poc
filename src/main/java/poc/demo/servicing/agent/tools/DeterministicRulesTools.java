package poc.demo.servicing.agent.tools;

import poc.demo.servicing.agent.actions.ActionPublisher;
import poc.demo.servicing.agent.model.CustomerProfile;
import poc.demo.servicing.agent.model.WaiverEvaluationResult;
import poc.demo.servicing.agent.telemetry.TraceCollector;
import poc.demo.servicing.agent.telemetry.TurnState;
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
import java.math.RoundingMode;

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
    private final ActionPublisher      actions;

    public DeterministicRulesTools(
            @Value("${agent.rules.waiver.max-fee-amount}")       BigDecimal maxFeeAmount,
            @Value("${agent.rules.waiver.min-tenure-months}")    int minTenureMonths,
            @Value("${agent.rules.waiver.eligible-tiers}")       String eligibleTiersStr,
            @Value("${agent.rules.waiver.max-waivers-per-year}") int maxPriorWaivers,
            CustomerContextTools customerContextTools,
            TraceCollector traceCollector,
            TurnState turnState,
            ActionPublisher actions) {
        this.maxFeeAmount         = maxFeeAmount.setScale(2, RoundingMode.HALF_UP);
        this.minTenureMonths      = minTenureMonths;
        this.eligibleTiers        = Arrays.stream(eligibleTiersStr.split(","))
                .map(String::trim).collect(Collectors.toSet());
        this.maxPriorWaivers      = maxPriorWaivers;
        this.customerContextTools = customerContextTools;
        this.traceCollector       = traceCollector;
        this.turnState            = turnState;
        this.actions              = actions;
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
        // The amount comes from the caller (in agent planner mode, the model). An approval is only applied to a
        // fee that exists: found live, the model asked to waive $50.00 on an account with no late fee at all.
        if (result.eligible() && !hasUnpaidLateFee(profile, requestedWaiverAmount)) {
            result = deny("FEE_NOT_ON_LEDGER", String.format(
                    "There is no unpaid late fee of $%s on the account; only a late fee on the ledger can be "
                            + "waived per %s.", money(requestedWaiverAmount), POLICY_CITATION));
        }
        if (result.eligible()) {
            emitApprovalAuditEvent(customerId, requestedWaiverAmount, result);
        }

        turnState.recordTool("evaluateWaiverRules");
        turnState.recordWaiverOutcome(result.reasonCode());
        turnState.recordGroundedSource(result.policyCitation());
        traceCollector.record("TOOL_CALL: evaluateWaiverRules -> " + result.reasonCode(),
                System.currentTimeMillis() - start);
        return result;
    }

    /**
     * Eligibility check without applying the waiver (FR-3.10 billing investigation): same rules as
     * {@link #evaluateWaiverRules}, but writes no audit event and records no waiver outcome, so the
     * agent can tell the customer a fee is waivable without approving it.
     */
    @Tool(description = "Check whether a late fee is eligible for a waiver WITHOUT applying it. Use this when the "
            + "customer asks why their bill is high or what a charge is, but has not asked for a waiver. Writes no "
            + "audit event and approves nothing; tell the customer the fee is eligible and that they can ask to apply it.")
    public WaiverEvaluationResult checkEligibility(
            @ToolParam(description = "Customer account ID, e.g. cust_246") String customerId,
            @ToolParam(description = "Late fee amount in dollars, from the customer's ledger") BigDecimal amount) {
        long start = System.currentTimeMillis();
        WaiverEvaluationResult result = evaluate(customerContextTools.loadProfile(customerId), amount);
        turnState.recordTool("checkWaiverEligibility");
        turnState.recordGroundedSource(result.policyCitation());
        traceCollector.record("TOOL_CALL: checkWaiverEligibility -> " + result.reasonCode() + " (not applied)",
                System.currentTimeMillis() - start);
        return result;
    }

    /**
     * FR-9.1: an approval is published as a waiver action; the downstream system of record applies it (invoice
     * WAIVED, waiver count +1, audit row) and CDC brings the change back. A publish failure must not break the turn.
     */
    private void emitApprovalAuditEvent(String customerId, BigDecimal amount, WaiverEvaluationResult result) {
        if (actions == null) {
            return;
        }
        try {
            String invoiceId = customerContextTools.loadProfile(customerId).recentCharges().stream()
                    .filter(c -> "LATE_FEE".equalsIgnoreCase(c.chargeType()) && "UNPAID".equalsIgnoreCase(c.status())
                            && c.amount().compareTo(amount) == 0)
                    .map(CustomerProfile.LedgerEntry::invoiceId).findFirst().orElse(null);
            actions.publish("waiver", customerId, invoiceId, amount, result.reasonCode(), "agent:tier1",
                    invoiceId == null ? amount.toPlainString() : invoiceId, result.policyCitation());
        } catch (Exception e) {
            log.warn("Could not publish waiver action for {}: {}", customerId, e.getMessage());
        }
    }

    private static boolean hasUnpaidLateFee(CustomerProfile profile, BigDecimal amount) {
        return profile.recentCharges().stream().anyMatch(c -> "LATE_FEE".equalsIgnoreCase(c.chargeType())
                && "UNPAID".equalsIgnoreCase(c.status()) && c.amount().compareTo(amount) == 0);
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
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private WaiverEvaluationResult deny(String reasonCode, String explanation) {
        return new WaiverEvaluationResult(false, reasonCode, POLICY_CITATION, explanation);
    }
}
