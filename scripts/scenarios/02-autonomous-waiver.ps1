# Scenario 2: autonomous waiver. Java rules approve; the agent only explains and cites.
. "$PSScriptRoot\_common.ps1"
Require-Stack
Title "2. Autonomous waiver (Elena, cust_123): deterministic rules approve, reply cites policy"

$r = Chat "cust_123" "Why is my bill `$45 higher, and can you waive it?"
Check "RESOLVED_AUTONOMOUSLY" ($r.kpiImpact.deflectionStatus -eq "RESOLVED_AUTONOMOUSLY") $r.kpiImpact.deflectionStatus
Check "evaluateWaiverRules -> APPROVED" (Trace-Has $r "TOOL_CALL: evaluateWaiverRules -> APPROVED")
Check "cites billing_waivers_policy.md#sec-4.2" ($r.citations -contains "billing_waivers_policy.md#sec-4.2")
Check "waiver request is not treated as a follow-up on Elena's open billing ticket" ($r.finalResponse -notmatch "already have an open ticket")
Finish
