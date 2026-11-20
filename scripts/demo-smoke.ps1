# End-to-end smoke test of the demo against a running stack (docker compose up --build -d).
# Usage: powershell -ExecutionPolicy Bypass -File scripts/demo-smoke.ps1
# On a rate-limited LLM (Gemini free tier, OpenAI trial) set $env:CHAT_PACE_SECONDS = 8 first (seconds between chat calls).
$ErrorActionPreference = "Stop"
$base = "http://localhost:8080"
$failures = 0

function Check([string]$name, [bool]$ok, [string]$detail = "") {
    if ($ok) { Write-Host "PASS  $name" -ForegroundColor Green }
    else { Write-Host "FAIL  $name  $detail" -ForegroundColor Red; $script:failures++ }
}
$script:LastChatAt = [DateTime]::MinValue
function Chat([string]$customerId, [string]$message) {
    $pace = 0; [void][int]::TryParse($env:CHAT_PACE_SECONDS, [ref]$pace)
    $wait = $pace - ([DateTime]::UtcNow - $script:LastChatAt).TotalSeconds
    if ($pace -gt 0 -and $wait -gt 0) { Start-Sleep -Milliseconds ([int]($wait * 1000)) }
    $script:LastChatAt = [DateTime]::UtcNow
    $body = @{ customerId = $customerId; message = $message } | ConvertTo-Json
    Invoke-RestMethod -Method Post "$base/api/agent/chat" -ContentType "application/json" -Body $body
}
function Profile([string]$customerId) {
    $json = docker exec redis redis-cli GET "customer:profile:$customerId"
    if ($json) { $json | ConvertFrom-Json } else { $null }
}
function WaitFor([scriptblock]$condition, [int]$seconds = 30) {
    for ($i = 0; $i -lt $seconds; $i++) { if (& $condition) { return $true }; Start-Sleep 1 }
    return $false
}

# 0. Initial Debezium snapshot hydrates Redis
Check "Initial snapshot hydrated all 3 personas" (WaitFor { (Profile "cust_123") -and (Profile "cust_456") -and (Profile "cust_789") } 60)

# 1. Live CDC mutation: $45 late fee for Elena
$before = (Profile "cust_123").lastAppliedLsn
$m = Invoke-RestMethod -Method Post "$base/api/demo/mutate" -ContentType "application/json" `
    -Body (@{ customerId = "cust_123"; chargeType = "LATE_FEE"; amount = 45.00; description = "Late fee (demo)" } | ConvertTo-Json)
$sw = [Diagnostics.Stopwatch]::StartNew()
$synced = WaitFor { (Profile "cust_123").recentCharges | Where-Object { $_.invoiceId -eq $m.invoiceId } } 20
Check "Step 1: CDC delivered $($m.invoiceId) to Redis in $($sw.ElapsedMilliseconds) ms" $synced
Check "Step 1: lastAppliedLsn advanced" ((Profile "cust_123").lastAppliedLsn -gt $before)

# 2. Elena: autonomous waiver
$r = Chat "cust_123" "Why is my bill `$45 higher, and can you waive it?"
Check "Step 2: RESOLVED_AUTONOMOUSLY" ($r.kpiImpact.deflectionStatus -eq "RESOLVED_AUTONOMOUSLY") $r.kpiImpact.deflectionStatus
Check "Step 2: evaluateWaiverRules -> APPROVED" ([bool]($r.agentReasoningTrace.event -contains "TOOL_CALL: evaluateWaiverRules -> APPROVED"))
Check "Step 2: cites billing_waivers_policy.md#sec-4.2" ($r.citations -contains "billing_waivers_policy.md#sec-4.2")
Check "Step 2: latency $($r.kpiImpact.totalTurnLatencyMs) ms < 1500" ($r.kpiImpact.totalTurnLatencyMs -lt 1500)

# 3. Marcus: ERR-504-GW conflict resolution + SLA escalation
$r = Chat "cust_456" "My gateway is throwing ERR-504-GW, what should I do?"
Check "Step 3: ESCALATED" ($r.kpiImpact.deflectionStatus -eq "ESCALATED") $r.kpiImpact.deflectionStatus
Check "Step 3: cites troubleshooting_guide.md#sec-1.1" ($r.citations -contains "troubleshooting_guide.md#sec-1.1")
Check "Step 3: legacy case marked superseded" ($r.finalResponse -match "superseded")
Check "Step 3: handoff card has TKT-8841 + SLA_BREACH" (($r.handoffCard.openTickets.ticketId -contains "TKT-8841") -and $r.handoffCard.detectedSentiment -eq "SLA_BREACH")
$r = Chat "cust_456" "Please waive the `$35 late fee"
Check "Step 3: second waiver denied (WAIVER_LIMIT_REACHED)" ([bool]($r.agentReasoningTrace.event -contains "TOOL_CALL: evaluateWaiverRules -> WAIVER_LIMIT_REACHED"))

# Existing ticket: Marcus follows up on the same problem; the agent recognises TKT-8841 (FR-6.3)
$r = Chat "cust_456" "Hi again, the gateway is still throwing ERR-504-GW, any update?"
Check "Existing ticket: trace shows EXISTING_TICKET TKT-8841" ([bool]($r.agentReasoningTrace.event | Where-Object { $_ -like "TOOL_CALL: findMatchingOpenTicket -> EXISTING_TICKET TKT-8841*" }))
Check "Existing ticket: reply names TKT-8841 as a follow-up" ($r.finalResponse -match "TKT-8841")

# Duplicate ticket prevented: same problem again for Marcus creates nothing (FR-6.4)
$countSql = "SELECT COUNT(*) FROM support_tickets WHERE customer_id='cust_456'"
$beforeCount = [int](docker exec warehouse-db psql -U warehouse_admin -d enterprise_warehouse -tAc $countSql)
$t = Invoke-RestMethod -Method Post "$base/api/demo/tickets" -ContentType "application/json" -Body (@{
    customerId = "cust_456"; category = "GATEWAY_OUTAGE"; subject = "Gateway still returning ERR-504-GW"
    status = "OPEN"; slaHoursFromNow = 24 } | ConvertTo-Json)
Check "Duplicate ticket: returned existing TKT-8841 (duplicate=$($t.duplicate), $($t.matchReason))" ($t.duplicate -and $t.ticketId -eq "TKT-8841")
$afterCount = [int](docker exec warehouse-db psql -U warehouse_admin -d enterprise_warehouse -tAc $countSql)
Check "Duplicate ticket: no new row ($beforeCount -> $afterCount)" ($afterCount -eq $beforeCount)

# Jordan: clean denial
$r = Chat "cust_789" "Can you waive my late fee?"
Check "Jordan: TIER_INELIGIBLE, no handoff" (($r.agentReasoningTrace.event -contains "TOOL_CALL: evaluateWaiverRules -> TIER_INELIGIBLE") -and -not $r.handoffCard)

# Cache miss: evict Elena, chat, verify fallback + re-hydration
Invoke-RestMethod -Method Delete "$base/api/demo/cache/cust_123" | Out-Null
$r = Chat "cust_123" "What is my latest charge?"
Check "Cache miss: trace shows warehouse fallback" ([bool]($r.agentReasoningTrace.event | Where-Object { $_ -like "*CACHE_MISS -> WAREHOUSE_FALLBACK*" }))
Check "Cache miss: latency $($r.kpiImpact.totalTurnLatencyMs) ms < 1500" ($r.kpiImpact.totalTurnLatencyMs -lt 1500)
Check "Cache miss: Redis re-hydrated" (WaitFor { Profile "cust_123" } 5)

# Supervisor override (demo mode: no IdP) and audit trail
$o = Invoke-RestMethod -Method Post "$base/api/admin/overrides/waiver" -ContentType "application/json" -Body (@{
    customerId = "cust_456"; invoiceId = "INV-2025-456-001"; amount = 35.00
    justification = "Goodwill credit during ERR-504-GW outage" } | ConvertTo-Json)
Check "Override applied with audit id $($o.auditId)" ($o.status -eq "APPLIED" -and $o.auditId)
$audit = docker exec warehouse-db psql -U warehouse_admin -d enterprise_warehouse -tAc "SELECT action||'|'||supervisor_sub FROM audit_log WHERE audit_id=$($o.auditId)"
Check "Audit row written ($audit)" ($audit -eq "WAIVER_OVERRIDE|demo-mode-no-idp")
$ErrorActionPreference = "Continue"
$del = docker exec warehouse-db psql -U warehouse_admin -d enterprise_warehouse -tAc "DELETE FROM audit_log WHERE audit_id=$($o.auditId)" 2>&1 | Out-String
$ErrorActionPreference = "Stop"
Check "Audit log rejects DELETE" ("$del" -match "append-only")
Check "Override reached Redis via CDC (invoice WAIVED)" (WaitFor { ((Profile "cust_456").recentCharges | Where-Object { $_.invoiceId -eq "INV-2025-456-001" }).status -eq "WAIVED" } 20)

# Validation
try { Invoke-RestMethod -Method Post "$base/api/agent/chat" -ContentType "application/json" -Body '{"customerId":"bad id!","message":""}'; Check "Bad request rejected" $false }
catch { Check "Bad request -> 400" ($_.Exception.Response.StatusCode.value__ -eq 400) }

# KPIs
$k = Invoke-RestMethod "$base/api/agent/reports/kpis"
Write-Host ("KPIs: sessions={0} deflections={1} escalations={2} deflectionRate={3}% ahtSaved={4}m compliance={5}% cacheMisses={6} dltDepth={7}" -f `
    $k.totalSessions, $k.autonomousDeflections, $k.escalations, $k.deflectionRatePercent, $k.totalAhtMinutesSaved,
    $k.policyComplianceScore, $k.cacheMissFallbacks, $k.dltMessageDepth)
Check "KPI report populated" ($k.totalSessions -ge 5 -and $k.cacheMissFallbacks -ge 1)
Check "KPI report exposes prompt-cache fields" ((@("llmCalls","promptTokens","cachedPromptTokens","promptCacheHitRatePercent") | Where-Object { $k.PSObject.Properties.Name -notcontains $_ }).Count -eq 0)

Write-Host ""
if ($failures -eq 0) { Write-Host "ALL CHECKS PASSED" -ForegroundColor Green; exit 0 }
else { Write-Host "$failures CHECK(S) FAILED" -ForegroundColor Red; exit 1 }
