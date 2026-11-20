# Scenario 4: clean denials. Ineligible requests are refused by rule, without a needless escalation.
. "$PSScriptRoot\_common.ps1"
Require-Stack
Title "4. Clean denials (Jordan cust_789: Standard tier; Marcus cust_456: waiver already used)"

$r = Chat "cust_789" "Can you waive my late fee?"
Check "Jordan: TIER_INELIGIBLE" (Trace-Has $r "TOOL_CALL: evaluateWaiverRules -> TIER_INELIGIBLE")
Check "Jordan: resolved without a handoff" ($r.kpiImpact.deflectionStatus -eq "RESOLVED_AUTONOMOUSLY" -and -not $r.handoffCard)

$r = Chat "cust_456" "Please waive the `$35 late fee"
Check "Marcus: WAIVER_LIMIT_REACHED" (Trace-Has $r "TOOL_CALL: evaluateWaiverRules -> WAIVER_LIMIT_REACHED")
Finish
