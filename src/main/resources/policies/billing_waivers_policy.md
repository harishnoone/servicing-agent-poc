# Billing Waivers Policy

**Document ID:** billing_waivers_policy.md  
**Owner:** Revenue Operations  
**Effective Date:** 2025-01-01  
**Review Cycle:** Annual  

---

## 1. Purpose

This policy defines the conditions under which Tier-1 and Tier-2 servicing agents are authorised to waive late payment fees on customer accounts. It exists to protect revenue integrity while giving long-tenured, high-value customers a consistent and fair remedy for isolated billing delays.

---

## 2. Scope

Applies to all customer accounts on the Enterprise Data Platform. Governs automated agent decisions (Tier-1) and supervisor overrides (Tier-2). Does not apply to recurring subscription credits, SLA credits (see `sla_credit_policy.md`), or promotional discounts.

---

## 3. Definitions

| Term | Meaning |
|------|---------|
| **Late Fee** | A one-time charge applied when a subscription invoice is not settled within the payment-due window (typically 30 days). |
| **Rolling 12-month window** | The 12 calendar months immediately preceding the date of the waiver request. |
| **Waiver** | Removal of a late fee from a customer's billing ledger with no payment required. |
| **Tier-2 Approval** | Written authorisation from a designated supervisor before a waiver is granted outside of policy bounds. |

---

## 4. Eligibility and Rules

### 4.1 Automatic Denial Conditions

A waiver request must be denied without further evaluation if **any** of the following are true:

- The customer's account tier is **Standard**.
- The fee amount exceeds **$50.00**.
- The customer has already received **1 or more waivers** in the rolling 12-month window.
- The account status is not `ACTIVE`.

### 4.2 Late Fee Waiver Eligibility

A Tier-1 agent may approve a late-fee waiver **without supervisor involvement** when **all** of the following criteria are satisfied simultaneously:

1. **Account tier:** `Gold` or `Enterprise`.
2. **Tenure:** The customer's `tenure_months` value is **≥ 12 months** as recorded in the customer master.
3. **Fee amount:** The specific late fee being waived is **≤ $50.00**.
4. **Prior waivers:** `waivers_used_12m` is **0** (no waivers granted in the rolling 12-month period).

When all four conditions are met, the agent shall:

- Record the waiver decision in the billing ledger (`status → WAIVED`).
- Increment `waivers_used_12m` by 1 on the customer record.
- Log the decision with justification for audit trail purposes.

**One waiver per 12-month rolling period.** A customer who has received a waiver may not receive another until the original waiver date falls outside the rolling window.

### 4.3 Out-of-Policy Discretionary Waivers

If a customer meets some but not all eligibility criteria in §4.2, the Tier-1 agent **must not** approve the waiver autonomously. The case must be:

1. Escalated to a **Tier-2 supervisor** with a summary of which criteria are unmet.
2. Held pending written approval from the supervisor before any ledger change is made.

Tier-2 supervisors may approve discretionary waivers at their sole discretion, subject to their own authority limits. Any out-of-policy waiver must be flagged in the monthly revenue-operations review.

---

## 5. Audit and Compliance

All waiver decisions—approved, denied, or escalated—must produce a durable `AgentTurnReport` entry capturing the policy version evaluated, criteria outcomes, and the final decision. This record is immutable once written.

---

## 6. Version History

| Version | Date | Change |
|---------|------|--------|
| 1.0 | 2025-01-01 | Initial release |
