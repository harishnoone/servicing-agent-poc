# Shared helpers for the single-scenario demo scripts. Dot-sourced; not meant to be run directly.
# Each scenario script prints what it does, shows the agent's trace and reply, runs PASS/FAIL checks,
# and exits 0 when every check passed. Set $env:BASE_URL for another host and $env:CHAT_PACE_SECONDS
# when the agent runs on a rate-limited LLM.
$ErrorActionPreference = "Stop"
$Base = if ($env:BASE_URL) { $env:BASE_URL.TrimEnd("/") } else { "http://localhost:8080" }
$script:Failures = 0
$script:LastChatAt = [DateTime]::MinValue

function Title([string]$text) {
    Write-Host ""
    Write-Host ("=" * 78) -ForegroundColor DarkCyan
    Write-Host " $text" -ForegroundColor Cyan
    Write-Host ("=" * 78) -ForegroundColor DarkCyan
}
function Say([string]$text) { Write-Host "`n$text" -ForegroundColor White }

function Check([string]$name, [bool]$ok, [string]$detail = "") {
    if ($ok) { Write-Host "  PASS  $name" -ForegroundColor Green }
    else { Write-Host "  FAIL  $name  $detail" -ForegroundColor Red; $script:Failures++ }
}

function Api([string]$Method, [string]$Path, $Body = $null) {
    $req = @{ Method = $Method; Uri = "$Base$Path"; ContentType = "application/json; charset=utf-8"; UseBasicParsing = $true }
    if ($null -ne $Body) { $req.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 5)) }
    $r = Invoke-WebRequest @req
    $text = [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
    if ($text) { $text | ConvertFrom-Json } else { $null }
}

function Chat([string]$customerId, [string]$message) {
    $pace = 0; [void][int]::TryParse($env:CHAT_PACE_SECONDS, [ref]$pace)
    $wait = $pace - ([DateTime]::UtcNow - $script:LastChatAt).TotalSeconds
    if ($pace -gt 0 -and $wait -gt 0) { Start-Sleep -Milliseconds ([int]($wait * 1000)) }
    Write-Host "`n  [$customerId] $message" -ForegroundColor White
    $r = Api POST "/api/agent/chat" @{ customerId = $customerId; message = $message }
    $script:LastChatAt = [DateTime]::UtcNow
    Show-Turn $r
    return $r
}

function Show-Turn($r) {
    $k = $r.kpiImpact
    $color = switch ($k.deflectionStatus) { "RESOLVED_AUTONOMOUSLY" { "Green" } "ESCALATED" { "Yellow" } default { "Red" } }
    Write-Host ("  {0}  ({1} ms)" -f $k.deflectionStatus, $k.totalTurnLatencyMs) -ForegroundColor $color
    $r.agentReasoningTrace | Where-Object { $_.event -like "TOOL_CALL*" -or $_.event -like "LLM_*" } |
        ForEach-Object { Write-Host ("    {0,-72} {1,4} ms" -f $_.event, $_.latencyMs) -ForegroundColor DarkGray }
    if ($r.citations) { Write-Host "  citations: $($r.citations -join ', ')" -ForegroundColor Cyan }
    if ($r.handoffCard) {
        Write-Host ("  handoff: {0}, urgency {1}" -f $r.handoffCard.detectedSentiment, $r.handoffCard.urgencyScore) -ForegroundColor Yellow
    }
    Write-Host "  > $($r.finalResponse)"
}

function Trace-Has($r, [string]$pattern) { [bool]($r.agentReasoningTrace.event | Where-Object { $_ -like $pattern }) }

# Latency budget per turn: 1500 ms offline (KPI target), 9000 ms when a live model wrote the reply
# (8 s synthesis timeout plus tools). Read from the turn's trace. $env:SMOKE_MAX_LATENCY_MS overrides.
function LatencyBudget($r) {
    $override = 0
    if ([int]::TryParse($env:SMOKE_MAX_LATENCY_MS, [ref]$override) -and $override -gt 0) { return $override }
    if (Trace-Has $r "PLANNER: LLM*") { return 9000 }
    return 1500
}
function CheckLatency([string]$label, $r) {
    $budget = LatencyBudget $r
    $mode = if ($budget -eq 9000) { "live LLM" } elseif ($budget -eq 1500) { "offline" } else { "override" }
    Check "${label} latency $($r.kpiImpact.totalTurnLatencyMs) ms < $budget ($mode)" ($r.kpiImpact.totalTurnLatencyMs -lt $budget)
}

function Profile([string]$customerId) {
    try { Api GET "/api/demo/profile/$customerId" } catch { $null }
}

function WaitFor([scriptblock]$condition, [int]$seconds = 20) {
    for ($i = 0; $i -lt $seconds * 2; $i++) { if (& $condition) { return $true }; Start-Sleep -Milliseconds 500 }
    return $false
}

function Sql([string]$query) {
    docker exec warehouse-db psql -U warehouse_admin -d enterprise_warehouse -tAc $query
}

function Require-Stack {
    try { $null = Invoke-WebRequest "$Base/actuator/health" -UseBasicParsing -TimeoutSec 5 }
    catch { Write-Host "App is not reachable at $Base. Start it with: docker compose up --build -d" -ForegroundColor Red; exit 2 }
}

function Finish {
    Write-Host ""
    if ($script:Failures -eq 0) { Write-Host "  SCENARIO PASSED" -ForegroundColor Green; exit 0 }
    Write-Host "  $($script:Failures) CHECK(S) FAILED" -ForegroundColor Red; exit 1
}
