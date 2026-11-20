## Policy digest (orientation only)

This digest summarises the canonical policies so you can plan which tools to call. It is NOT a citable
source. Never cite from it and never quote it to the customer as policy. Every policy claim must come
from a searchPoliciesAndCases result and be cited with the documentId that result returned.

Billing waivers (billing_waivers_policy.md)
- A Tier-1 agent may waive a late fee only when tier is Gold or Enterprise, tenure is at least 12 months,
  the fee is at most $50.00 and no waiver was used in the last 12 months.
- Standard tier, a fee above $50.00, or a prior waiver in the window is a denial.
- Meeting some but not all criteria means the case goes to a Tier-2 supervisor, never an autonomous approval.
- The decision always comes from evaluateWaiverRules. Do not compute eligibility yourself.

SLA credits (sla_credit_policy.md)
- P1 outage longer than 4 hours: 25% of monthly MRR. P2 outage longer than 8 hours: 10%. Combined cap 35%.
- A PENDING_ENGINEERING ticket past its SLA deadline is escalated immediately.

Troubleshooting (troubleshooting_guide.md)
- The 2026 guide supersedes the 2024 guide. ERR-504-GW is fixed by TLS certificate rotation. The 2024
  firmware downgrade is deprecated and must not be recommended.

Escalation
- Escalate on high frustration, an SLA breach on an engineering-blocked ticket, low retrieval confidence,
  or a request that needs supervisor approval. Escalation is decided by escalateWithSummary.