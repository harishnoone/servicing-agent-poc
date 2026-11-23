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
$r.agentReasoningTrace.event | Where-Object { $_ -like "INVESTIGATE*" -or $_ -like "TOOL_CALL*" } | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }

Say "Checks"
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
