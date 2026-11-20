# SLA Credit Policy

**Document ID:** sla_credit_policy.md  
**Owner:** Customer Success Operations  
**Effective Date:** 2025-01-01  
**Review Cycle:** Annual  

---

## 1. Purpose

This policy defines the credit entitlements customers may claim when platform availability falls below contracted SLA thresholds. It governs automated agent decisions for straightforward credit calculations and defines the escalation path when engineering blockages prevent timely resolution.

---

## 2. Scope

Applies to all production tenants on the Enterprise Data Platform. Does not cover scheduled maintenance windows published at least 72 hours in advance, force-majeure events, or incidents caused by customer-side misconfiguration.

---

## 3. Outage Classification

| Priority | Description | Contracted Uptime Threshold |
|----------|-------------|----------------------------|
| **P1** | Full service unavailability or data-path outage affecting all end-users | 99.9 % monthly |
| **P2** | Partial outage or degraded performance affecting a subset of users or non-critical features | 99.5 % monthly |
| **P3** | Minor degradation; workaround available | Best-effort |

---

## 4. Credit Entitlements

### 4.1 P1 Outage Credit

When a P1 incident has a confirmed duration exceeding **4 hours** in a single calendar month:

- The customer is entitled to a **25 % credit against that month's MRR**.
- The credit is calculated on the base subscription MRR, excluding one-time fees, usage overages, and professional-services charges.
- Credits are applied to the next billing cycle; they are non-transferable and have no cash value.

### 4.2 P2 Outage Credit

When a P2 incident has a confirmed duration exceeding **8 hours** in a single calendar month:

- The customer is entitled to a **10 % credit against that month's MRR**.
- The same calculation and application rules as §4.1 apply.

### 4.3 Stacking Limits

A customer may not receive combined P1 and P2 credits exceeding **35 % of MRR** in any single calendar month. Where incidents overlap, the higher priority credit takes precedence for the overlapping period.

---

## 5. Engineering-Blocked Ticket Escalation

An engineering-blocked ticket is one with `status = PENDING_ENGINEERING` in the support-ticket system.

**Automatic escalation rule:** Any engineering-blocked ticket whose `sla_deadline` has passed without resolution must be escalated immediately to the on-call Engineering Lead and the customer's Account Executive. The Tier-1 servicing agent shall:

1. Detect the missed SLA by comparing `sla_deadline` to the current timestamp.
2. Flag the ticket as `ESCALATED` in the support system.
3. Generate an `AgentTurnReport` documenting the escalation trigger and timestamp.
4. Notify the customer that the incident has been escalated and provide an estimated response window per the incident runbook.

Manual override of an automatic escalation requires Tier-2 supervisor authorisation.

---

## 6. Credit Request Process

1. The Tier-1 agent verifies the incident record and confirmed outage duration from the monitoring system.
2. The agent calculates the credit amount using the customer's current MRR and the applicable percentage from §4.1 or §4.2.
3. The credit is recorded in the billing ledger as `charge_type = SLA_CREDIT`, `status = PENDING_APPROVAL`.
4. A Tier-2 supervisor confirms the credit within one business day; upon confirmation, `status` transitions to `APPLIED`.

---

## 7. Version History

| Version | Date | Change |
|---------|------|--------|
| 1.0 | 2025-01-01 | Initial release |
