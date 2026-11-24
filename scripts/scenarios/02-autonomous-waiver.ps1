# Scenario 2: autonomous waiver as an action. Java rules approve, the agent publishes a waiver action, the system of record applies it.
. "$PSScriptRoot\_common.ps1"
Require-Stack
Title "2. Autonomous waiver (Elena, cust_123): rules approve -> Kafka action -> system of record -> CDC"

# Rerunnable: an approved waiver is now really applied, so start each run with 0 waivers and a fresh unpaid $45 late fee.
$null = Api POST "/api/demo/customers/cust_123" @{ waiversUsed12m = 0 }
$fee = Api POST "/api/demo/mutate" @{ customerId = "cust_123"; chargeType = "LATE_FEE"; amount = 45.00; description = "Late fee (scenario 2)" }
$ready = WaitFor { $p = Profile "cust_123"; $p.waiversUsed12m -eq 0 -and ($p.recentCharges | Where-Object { $_.invoiceId -eq $fee.invoiceId }) } 20
Check "setup via CDC: 0 waivers, new late fee $($fee.invoiceId)" $ready

$r = Chat "cust_123" "Why is my bill `$45 higher, and can you waive it?"
Check "RESOLVED_AUTONOMOUSLY" ($r.kpiImpact.deflectionStatus -eq "RESOLVED_AUTONOMOUSLY") $r.kpiImpact.deflectionStatus
Check "evaluateWaiverRules -> APPROVED" (Trace-Has $r "TOOL_CALL: evaluateWaiverRules -> APPROVED")
Check "cites billing_waivers_policy.md#sec-4.2" ($r.citations -contains "billing_waivers_policy.md#sec-4.2")
Check "waiver request is not treated as a follow-up on Elena's open billing ticket" ($r.finalResponse -notmatch "already have an open ticket")
Check "waiver action published to agent.actions.waiver" (Trace-Has $r "ACTION_PUBLISHED: waiver *")

Say "The system of record applies the action; CDC brings the change back to the agent's cache:"
Check "invoice $($fee.invoiceId) is WAIVED in Redis" (WaitFor { ((Profile "cust_123").recentCharges | Where-Object { $_.invoiceId -eq $fee.invoiceId }).status -eq "WAIVED" } 20)
Check "waiver count is now 1" ((Profile "cust_123").waiversUsed12m -eq 1)
$audit = Sql "SELECT action||'|'||supervisor_sub FROM audit_log WHERE invoice_id='$($fee.invoiceId)' ORDER BY audit_id DESC LIMIT 1"
Check "audit row from the system of record ($audit)" ($audit -eq "WAIVER_APPROVED_AUTONOMOUS|agent:tier1")
docker logs servicing-agent-app --since 2m 2>&1 | Select-String "Action produced" | Select-Object -Last 1 | ForEach-Object { Write-Host "  $($_.Line.Trim() -replace '^.*?: ','')" -ForegroundColor DarkGray }
Finish
