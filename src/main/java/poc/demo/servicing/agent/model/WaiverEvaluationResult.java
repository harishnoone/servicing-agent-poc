package poc.demo.servicing.agent.model;

/**
 * Deterministic waiver eligibility result emitted by DeterministicRulesTools.
 * Passed back to the LLM context so the agent communicates an auditable,
 * policy-backed decision — never delegates the approval itself to the LLM (FR-5.3).
 */
public record WaiverEvaluationResult(
        boolean eligible,
        String reasonCode,       // APPROVED | TIER_INELIGIBLE | TENURE_TOO_SHORT | FEE_TOO_HIGH | WAIVER_LIMIT_REACHED
        String policyCitation,   // e.g. "billing_waivers_policy.md#sec-4.2"
        String explanation
) {}
