# Scenario 11: multi-step billing investigation (Riley, cust_246). Billing -> tickets -> waiver rules -> SLA policy -> verify.
. "$PSScriptRoot\_common.ps1"
Require-Stack
Title "11. Multi-step investigation: 'Why is my bill so high?' (Riley Morgan, cust_246, Enterprise)"

# Self-seeding through CDC, so the scenario works on a fresh stack without 'demo.ps1 seed'.
if (-not (Profile "cust_246")) {
    Say "Creating Riley in the warehouse: Enterprise, 30 months, `$180 overage, `$35 late fee, open P1 outage ticket..."
    try {
        $null = Api POST "/api/demo/customers" @{ customerId = "cust_246"; fullName = "Riley Morgan"; accountTier = "Enterprise"
            tenureMonths = 30; mrr = 1200.00; waiversUsed12m = 0 }
        $null = Api POST "/api/demo/mutate" @{ customerId = "cust_246"; chargeType = "OVERAGE"; amount = 180.00; description = "API overage, November" }
        $null = Api POST "/api/demo/mutate" @{ customerId = "cust_246"; chargeType = "LATE_FEE"; amount = 35.00; description = "Late payment fee" }
    } catch { Write-Host "  (customer already in the warehouse; reusing it)" -ForegroundColor DarkGray }
    $null = Api POST "/api/demo/tickets" @{ customerId = "cust_246"; category = "GATEWAY_OUTAGE"
        subject = "P1 outage on EU gateway for 6 hours"; status = "OPEN"; slaHoursFromNow = 24 }
    $ready = WaitFor { $p = Profile "cust_246"; $p -and @($p.recentCharges).Count -ge 2 -and @($p.activeTickets).Count -ge 1 } 30
    Check "Riley's profile reached Redis via CDC (2 charges, 1 ticket)" $ready
}

Say "Riley asks one open question. The agent has to chain billing, tickets, rules and policy to answer it:"
$r = Chat "cust_246" "Why is my bill so high this month?"

Say "Investigation steps in the trace:"
$r.agentReasoningTrace.event | Where-Object { $_ -like "INVESTIGATE*" -or $_ -like "TOOL_CALL*" -or $_ -like "AGENTIC_*" -or $_ -like "LLM_ROUND_TRIP*" } | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }

Say "Checks"
if (Trace-Has $r "AGENTIC_FALLBACK*") {
    # A model call failed or hit the 15 s read timeout; Java returned the deterministic investigation instead.
    Write-Host "  Provider call failed or timed out: Java returned the deterministic investigation (not model-planned this turn)." -ForegroundColor Yellow
    Check "fallback answer is the verified deterministic investigation" (Trace-Has $r "INVESTIGATE 5/5 VERIFIED*")
    Check "no waiver was approved (no evaluateWaiverRules call)" (-not (Trace-Has $r "TOOL_CALL: evaluateWaiverRules*"))
    CheckLatency "turn" $r
    Finish
}
if (Trace-Has $r "PLANNER: LLM_AGENTIC") {
    # The model planned the steps itself; Java still decided every outcome and checked the answer.
    $tools = @($r.agentReasoningTrace.event | Where-Object { $_ -like "TOOL_CALL*" })
    $trips = @($r.agentReasoningTrace.event | Where-Object { $_ -like "LLM_ROUND_TRIP*" }).Count
    Write-Host "  Model-planned: $trips model calls, $($tools.Count) tool calls" -ForegroundColor Cyan
    Check "model loaded the account (getCustomerState)" (Trace-Has $r "TOOL_CALL: getCustomerState*")
    Check "model checked the late fee with checkWaiverEligibility, NOT applied" (Trace-Has $r "TOOL_CALL: checkWaiverEligibility -> APPROVED (not applied)")
    Check "no waiver was approved (no evaluateWaiverRules call)" (-not (Trace-Has $r "TOOL_CALL: evaluateWaiverRules*"))
    Check "model searched the policies" (Trace-Has $r "TOOL_CALL: searchPoliciesAndCases*")
    Check "Java verified the model's answer (AGENTIC_VERIFIED)" (Trace-Has $r "AGENTIC_VERIFIED*")
    Check "answer reports both charges (`$180.00 and `$35.00)" (($r.finalResponse -match '\$180(\.00)?') -and ($r.finalResponse -match '\$35(\.00)?'))
    Check "cites the waiver policy" ($r.citations -contains "billing_waivers_policy.md#sec-4.2")
    Write-Host ("  Optional steps the model chose: open-ticket check={0}, SLA policy cited={1}" -f `
        (Trace-Has $r "TOOL_CALL: findMatchingOpenTicket*"), ($r.citations -contains "sla_credit_policy.md#sec-4.1")) -ForegroundColor DarkGray
    CheckLatency "turn" $r
    Finish
}
Check "step 1 billing: both charges found, `$215.00 total" (Trace-Has $r "INVESTIGATE 1/5 billing: 2 unpaid charge(s)*total `$215.00")
Check "step 2 tickets: open outage ticket checked" (Trace-Has $r "INVESTIGATE 2/5 tickets: * open*")
Check "step 3 rules: late fee eligible, NOT applied" (Trace-Has $r "TOOL_CALL: checkWaiverEligibility -> APPROVED (not applied)")
Check "no waiver was approved (no evaluateWaiverRules call)" (-not (Trace-Has $r "TOOL_CALL: evaluateWaiverRules*"))
Check "step 4 policy: SLA credit policy checked for the outage" (Trace-Has $r "INVESTIGATE 4/5 policy: SLA credit checked*")
Check "step 5 verified: every amount, invoice, ticket and citation matches tool results" (Trace-Has $r "INVESTIGATE 5/5 VERIFIED*")
Check "cites the waiver and SLA-credit policies" (($r.citations -contains "billing_waivers_policy.md#sec-4.2") -and ($r.citations -contains "sla_credit_policy.md#sec-4.1"))
Check "resolved without a handoff" ($r.kpiImpact.deflectionStatus -eq "RESOLVED_AUTONOMOUSLY")
CheckLatency "turn" $r
Finish
