<#
.SYNOPSIS
  Seed test data and drive the Servicing Agent demo from the command line.

.EXAMPLE
  .\scripts\demo.ps1 seed                                   # load scripts/seed-data.txt
  .\scripts\demo.ps1 scenarios                              # ask every CHAT question in seed-data.txt
  .\scripts\demo.ps1 charge cust_123 LATE_FEE 45            # insert one charge
  .\scripts\demo.ps1 customer cust_900 "Dana Lee" Gold 40 499 [waivers]
  .\scripts\demo.ps1 ticket cust_900 PENDING_ENGINEERING -3 "Gateway down"   # negative hours = SLA breached
  .\scripts\demo.ps1 update cust_900 tier=Enterprise tenure=50 waivers=1
  .\scripts\demo.ps1 chat cust_900 "Can you waive my late fee?"
  .\scripts\demo.ps1 profile cust_900                       # what the agent sees in Redis
  .\scripts\demo.ps1 evict cust_900                         # simulate a dormant account (cache miss)
  .\scripts\demo.ps1 poison [customers|billing_ledger|support_tickets]
  .\scripts\demo.ps1 caching [customerId] [turns]            # prompt-caching demo: same question N times, LLM timing + tokens
  .\scripts\demo.ps1 dlt | replay [table] | kpis | reset

  Set $env:BASE_URL to target another host (default http://localhost:8080).
  Set $env:CHAT_PACE_SECONDS (e.g. 8) to leave at least that many seconds between chat calls when the agent runs on a
  rate-limited LLM (Gemini free tier, OpenAI trial). Default 0 = no pacing (offline planner needs none).
  Set $env:CHAT_RETRY_WAIT_SECONDS (default 30) = wait before the single retry of a turn the LLM provider rejected.
#>
param(
    [Parameter(Position = 0)] [string] $Command = "help",
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)] [string[]] $Rest
)
$ErrorActionPreference = "Stop"
$Base = if ($env:BASE_URL) { $env:BASE_URL.TrimEnd("/") } else { "http://localhost:8080" }
$SeedFile = Join-Path $PSScriptRoot "seed-data.txt"

function Api([string]$Method, [string]$Path, $Body = $null) {
    # Invoke-WebRequest + explicit UTF-8 decode: Windows PowerShell 5 otherwise decodes JSON as Latin-1.
    $req = @{ Method = $Method; Uri = "$Base$Path"; ContentType = "application/json; charset=utf-8"; UseBasicParsing = $true }
    if ($null -ne $Body) {
        $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 5 }
        $req.Body = [Text.Encoding]::UTF8.GetBytes($json)
    }
    try {
        $r = Invoke-WebRequest @req
        $text = [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
        return [pscustomobject]@{ Ok = $true; Status = [int]$r.StatusCode; Data = $(if ($text) { $text | ConvertFrom-Json } else { $null }) }
    } catch {
        $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        $msg = if ($_.ErrorDetails.Message) { $_.ErrorDetails.Message } else { $_.Exception.Message }
        return [pscustomobject]@{ Ok = $false; Status = $status; Data = $msg }
    }
}
function Require([int]$n, [string]$usage) {
    if ($Rest.Count -lt $n) { Write-Host "usage: demo.ps1 $usage" -ForegroundColor Yellow; exit 2 }
}

function Show-Turn($r) {
    $k = $r.kpiImpact
    $color = switch ($k.deflectionStatus) { "RESOLVED_AUTONOMOUSLY" { "Green" } "ESCALATED" { "Yellow" } default { "Red" } }
    Write-Host ("  {0}  ({1} ms)" -f $k.deflectionStatus, $k.totalTurnLatencyMs) -ForegroundColor $color
    $r.agentReasoningTrace | Where-Object { $_.event -like "TOOL_CALL*" -or $_.event -like "LLM_*" } |
        ForEach-Object { Write-Host ("    {0,-70} {1,4} ms" -f $_.event, $_.latencyMs) -ForegroundColor DarkGray }
    if ($r.citations) { Write-Host "  citations: $($r.citations -join ', ')" -ForegroundColor Cyan }
    if ($r.handoffCard) {
        Write-Host ("  handoff: {0}, urgency {1}, next: {2}" -f $r.handoffCard.detectedSentiment, $r.handoffCard.urgencyScore,
            $r.handoffCard.recommendedNextAction) -ForegroundColor Yellow
    }
    Write-Host "  > $($r.finalResponse)"
}

$script:LastChatAt = [DateTime]::MinValue
function Pace {
    $pace = 0; [void][int]::TryParse($env:CHAT_PACE_SECONDS, [ref]$pace)
    if ($pace -le 0) { return }
    $wait = $pace - ([DateTime]::UtcNow - $script:LastChatAt).TotalSeconds
    if ($wait -gt 0) { Write-Host ("  (pacing: waiting {0:N0} s to stay under the LLM rate limit)" -f $wait) -ForegroundColor DarkGray; Start-Sleep -Milliseconds ([int]($wait * 1000)) }
}
function Test-ProviderFailure($data) {
    # The app turns a provider error (429, quota, outage) into PARTIAL with an AGENT_TURN_FAILED trace step.
    return ($data.kpiImpact.deflectionStatus -eq "PARTIAL") -and (($data.agentReasoningTrace | Where-Object { $_.event -like "AGENT_TURN_FAILED*" }).Count -gt 0)
}
function Chat([string]$customerId, [string]$message) {
    Write-Host "`n[$customerId] $message" -ForegroundColor White
    $retryWait = 30; [void][int]::TryParse($env:CHAT_RETRY_WAIT_SECONDS, [ref]$retryWait)
    Pace
    $res = Api POST "/api/agent/chat" @{ customerId = $customerId; message = $message }
    $script:LastChatAt = [DateTime]::UtcNow
    if ($res.Ok -and (Test-ProviderFailure $res.Data) -and $retryWait -gt 0) {
        Write-Host "  LLM provider rejected the turn (rate limit or quota). Waiting $retryWait s, then retrying once." -ForegroundColor Yellow
        Start-Sleep $retryWait
        $res = Api POST "/api/agent/chat" @{ customerId = $customerId; message = $message }
        $script:LastChatAt = [DateTime]::UtcNow
    }
    if ($res.Ok) { Show-Turn $res.Data } else { Write-Host "  HTTP $($res.Status): $($res.Data)" -ForegroundColor Red }
}

function Wait-Profile([string]$customerId, [int]$seconds = 20) {
    for ($i = 0; $i -lt $seconds * 2; $i++) {
        if ((Api GET "/api/demo/profile/$customerId").Ok) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Read-SeedLines {
    Get-Content $SeedFile | Where-Object { $_.Trim() -and -not $_.TrimStart().StartsWith("#") } | ForEach-Object {
        $parts = $_.Trim() -split "\s+", 3
        [pscustomobject]@{ Verb = $parts[0]; Target = $parts[1]; Body = $parts[2] }
    }
}

switch ($Command.ToLower()) {

    "seed" {
        $skipped = @{}; $created = @(); $ok = 0; $fail = 0
        foreach ($line in Read-SeedLines | Where-Object Verb -eq "POST") {
            $customerId = ($line.Body | ConvertFrom-Json).customerId
            if ($skipped.ContainsKey($customerId)) { continue }
            $res = Api POST $line.Target $line.Body
            if ($res.Ok) {
                $ok++
                if ($line.Target -eq "/api/demo/customers") { $created += $customerId }
                Write-Host ("  ok    {0,-22} {1}" -f $line.Target, $customerId) -ForegroundColor Green
            } elseif ($res.Status -eq 409) {
                $skipped[$customerId] = $true
                Write-Host ("  skip  {0,-22} {1} already exists" -f $line.Target, $customerId) -ForegroundColor DarkGray
            } else {
                $fail++
                Write-Host ("  FAIL  {0,-22} {1}: HTTP {2} {3}" -f $line.Target, $customerId, $res.Status, $res.Data) -ForegroundColor Red
            }
        }
        if ($created) {
            Write-Host "Waiting for CDC to deliver $($created.Count) new profile(s) to Redis..."
            foreach ($id in $created) {
                if (Wait-Profile $id) { Write-Host "  cached  $id" -ForegroundColor Green }
                else { Write-Host "  NOT cached after 20s: $id (is the Debezium connector running?)" -ForegroundColor Red; $fail++ }
            }
        }
        Write-Host "`nSeed done: $ok inserted, $($skipped.Count) customer(s) skipped, $fail failed."
        if ($fail) { exit 1 }
    }

    "scenarios" {
        foreach ($line in Read-SeedLines | Where-Object Verb -eq "CHAT") {
            $message = if ($line.Body) { $line.Body } else { "" }
            Chat $line.Target $message
        }
        $k = (Api GET "/api/agent/reports/kpis").Data
        Write-Host ("`nKPIs: sessions={0} deflection={1}% escalations={2} compliance={3}% cacheMisses={4} dltDepth={5}" -f `
            $k.totalSessions, $k.deflectionRatePercent, $k.escalations, $k.policyComplianceScore, $k.cacheMissFallbacks, $k.dltMessageDepth)
    }

    "charge" {
        Require 3 "charge <customerId> <SUBSCRIPTION|LATE_FEE|OVERAGE> <amount> [description]"
        $desc = if ($Rest.Count -gt 3) { ($Rest[3..($Rest.Count - 1)] -join " ") } else { "Added by demo.ps1" }
        $res = Api POST "/api/demo/mutate" @{ customerId = $Rest[0]; chargeType = $Rest[1].ToUpper(); amount = [decimal]$Rest[2]; description = $desc }
        if (-not $res.Ok) { Write-Host "HTTP $($res.Status): $($res.Data)" -ForegroundColor Red; exit 1 }
        Write-Host "Inserted $($res.Data.invoiceId). Waiting for it to reach Redis..."
        for ($i = 0; $i -lt 40; $i++) {
            $p = Api GET "/api/demo/profile/$($Rest[0])"
            if ($p.Ok -and ($p.Data.recentCharges.invoiceId -contains $res.Data.invoiceId)) {
                Write-Host "In Redis after ~$([int]($i * 0.5)) s (lastAppliedLsn $($p.Data.lastAppliedLsn))" -ForegroundColor Green; exit 0
            }
            Start-Sleep -Milliseconds 500
        }
        Write-Host "Not in Redis after 20 s" -ForegroundColor Red; exit 1
    }

    "customer" {
        Require 5 "customer <customerId> <fullName> <Standard|Gold|Enterprise> <tenureMonths> <mrr> [waiversUsed12m]"
        $waivers = if ($Rest.Count -gt 5) { [int]$Rest[5] } else { 0 }
        $res = Api POST "/api/demo/customers" @{ customerId = $Rest[0]; fullName = $Rest[1]; accountTier = $Rest[2]
            tenureMonths = [int]$Rest[3]; mrr = [decimal]$Rest[4]; waiversUsed12m = $waivers }
        if (-not $res.Ok) { Write-Host "HTTP $($res.Status): $($res.Data)" -ForegroundColor Red; exit 1 }
        if (Wait-Profile $Rest[0]) { Write-Host "Created $($Rest[0]) and cached in Redis" -ForegroundColor Green }
        else { Write-Host "Created, but not in Redis after 20 s" -ForegroundColor Red; exit 1 }
    }

    "ticket" {
        Require 4 "ticket <customerId> <OPEN|PENDING_ENGINEERING|RESOLVED> <slaHoursFromNow> <subject...>  (negative hours = breached)"
        $res = Api POST "/api/demo/tickets" @{ customerId = $Rest[0]; status = $Rest[1].ToUpper(); slaHoursFromNow = [int]$Rest[2]
            subject = ($Rest[3..($Rest.Count - 1)] -join " "); category = "TECHNICAL" }
        if (-not $res.Ok) { Write-Host "HTTP $($res.Status): $($res.Data)" -ForegroundColor Red; exit 1 }
        Write-Host "Created $($res.Data.ticketId) (SLA breached: $($res.Data.slaBreached)). Waiting for it to reach Redis..."
        for ($i = 0; $i -lt 40; $i++) {
            $p = Api GET "/api/demo/profile/$($Rest[0])"
            if ($p.Ok -and ($p.Data.activeTickets.ticketId -contains $res.Data.ticketId)) { Write-Host "In Redis" -ForegroundColor Green; exit 0 }
            Start-Sleep -Milliseconds 500
        }
        Write-Host "Not in Redis after 20 s" -ForegroundColor Red; exit 1
    }

    "update" {
        Require 2 "update <customerId> [tier=Gold] [tenure=24] [waivers=0]"
        $body = @{}
        foreach ($kv in $Rest[1..($Rest.Count - 1)]) {
            $k, $v = $kv -split "=", 2
            switch ($k) { "tier" { $body.accountTier = $v } "tenure" { $body.tenureMonths = [int]$v } "waivers" { $body.waiversUsed12m = [int]$v }
                default { Write-Host "unknown field '$k' (use tier, tenure, waivers)" -ForegroundColor Red; exit 2 } }
        }
        $res = Api POST "/api/demo/customers/$($Rest[0])" $body
        if ($res.Ok) { Write-Host "Updated $($Rest[0]); CDC will refresh Redis in about a second" -ForegroundColor Green }
        else { Write-Host "HTTP $($res.Status): $($res.Data)" -ForegroundColor Red; exit 1 }
    }

    "chat" {
        Require 2 "chat <customerId> <message...>"
        Chat $Rest[0] ($Rest[1..($Rest.Count - 1)] -join " ")
    }

    "profile" {
        Require 1 "profile <customerId>"
        $res = Api GET "/api/demo/profile/$($Rest[0])"
        if ($res.Ok) { $res.Data | ConvertTo-Json -Depth 5 } else { Write-Host "Not cached: $($Rest[0])" -ForegroundColor Yellow }
    }

    "evict" {
        Require 1 "evict <customerId>"
        $res = Api DELETE "/api/demo/cache/$($Rest[0])"
        Write-Host "Evicted: $($res.Data.evicted). Next chat for $($Rest[0]) will use the warehouse fallback."
    }

    "poison" {
        $table = if ($Rest.Count -gt 0) { $Rest[0] } else { "customers" }
        $res = Api POST "/api/demo/poison-pill" @{ table = $table }
        if (-not $res.Ok) { Write-Host "HTTP $($res.Status): $($res.Data)" -ForegroundColor Red; exit 1 }
        Write-Host "$($res.Data.message) Waiting..."
        $dlt = "db.public.$table.DLT"
        for ($i = 0; $i -lt 20; $i++) {
            Start-Sleep 1
            $d = (Api GET "/api/admin/kafka/dlt?preview=1").Data.$dlt
            if ($d.depth -gt 0) {
                Write-Host "On $dlt after ~$($i + 1) s. Depth $($d.depth). Cause: $($d.pending[0].exceptionClass)" -ForegroundColor Green; exit 0
            }
        }
        Write-Host "Not on $dlt after 20 s" -ForegroundColor Red; exit 1
    }

    "dlt"    { (Api GET "/api/admin/kafka/dlt?preview=3").Data | ConvertTo-Json -Depth 6 }

    "replay" {
        $table = if ($Rest.Count -gt 0) { $Rest[0] } else { "customers" }
        (Api POST "/api/admin/kafka/dlt/replay" @{ topic = "db.public.$table.DLT" }).Data | Format-List
    }

    "kpis"   { (Api GET "/api/agent/reports/kpis").Data | Format-List }

    "caching" {
        # Prompt-caching demo (FR-3.5 to FR-3.7): the same question several times, so turn 1 pays for the static
        # prefix and later turns can reuse it. Prints LLM round trips, latency and token usage per turn.
        $customer = if ($Rest.Count -gt 0) { $Rest[0] } else { "cust_123" }
        $turns = 3; if ($Rest.Count -gt 1) { [void][int]::TryParse($Rest[1], [ref]$turns) }
        if (-not $env:CHAT_PACE_SECONDS) { $env:CHAT_PACE_SECONDS = "5" }
        $question = "What is the maximum late fee you can waive for an account like mine?"
        $before = (Api GET "/api/agent/reports/kpis").Data
        $rows = @()
        for ($t = 1; $t -le $turns; $t++) {
            Write-Host "`nTurn $t of $turns  [$customer] $question" -ForegroundColor White
            Pace
            $res = Api POST "/api/agent/chat" @{ customerId = $customer; message = $question }
            $script:LastChatAt = [DateTime]::UtcNow
            if (-not $res.Ok) { Write-Host "  HTTP $($res.Status): $($res.Data)" -ForegroundColor Red; continue }
            $ev = $res.Data.agentReasoningTrace
            $planner = ($ev | Where-Object { $_.event -like "PLANNER:*" } | Select-Object -First 1).event
            $trips = @($ev | Where-Object { $_.event -like "LLM_ROUND_TRIP*" })
            $usage = ($ev | Where-Object { $_.event -like "LLM_USAGE*" } | Select-Object -First 1).event
            $outcome = ($ev | Where-Object { $_.event -like "LLM_SYNTHESIS*" } | Select-Object -First 1).event
            Write-Host ("  {0}  total {1} ms  planner: {2}" -f $res.Data.kpiImpact.deflectionStatus, $res.Data.kpiImpact.totalTurnLatencyMs, $planner)
            $trips | ForEach-Object { Write-Host ("    {0,-40} {1,6} ms" -f $_.event, $_.latencyMs) -ForegroundColor DarkGray }
            if ($usage) { Write-Host "    $usage" -ForegroundColor Cyan }
            if ($outcome) { Write-Host "    $outcome" -ForegroundColor DarkGray }
            $rows += [pscustomobject]@{ Turn = $t; TotalMs = $res.Data.kpiImpact.totalTurnLatencyMs; LlmRoundTrips = $trips.Count
                LlmMs = ($trips | Measure-Object latencyMs -Sum).Sum; Usage = $usage }
            if ($planner -eq "PLANNER: DETERMINISTIC_REPLAY") {
                Write-Host "  Offline planner: no LLM call, so there is nothing to cache. Start the app with AGENT_LLM_MODE=llm and a key (playbook section 4)." -ForegroundColor Yellow
                break
            }
        }
        $after = (Api GET "/api/agent/reports/kpis").Data
        Write-Host "`nSummary" -ForegroundColor White
        $rows | Format-Table -AutoSize | Out-String | Write-Host
        Write-Host ("  This run: llmCalls +{0}, promptTokens +{1}, cachedPromptTokens +{2}; cumulative hit rate {3}%" -f
            ($after.llmCalls - $before.llmCalls), ($after.promptTokens - $before.promptTokens),
            ($after.cachedPromptTokens - $before.cachedPromptTokens), $after.promptCacheHitRatePercent)
        Write-Host "  OpenAI reports cached tokens per call. Gemini's OpenAI-compatible endpoint does not, so on Gemini compare latency and promptTokens instead." -ForegroundColor DarkGray
    }

    "reset" {
        Push-Location (Split-Path $PSScriptRoot -Parent)
        try {
            $ErrorActionPreference = "Continue"
            docker compose down -v 2>&1 | Out-Null
            docker compose up -d 2>&1 | Out-Null
            Write-Host "Waiting for the app..."
            for ($i = 0; $i -lt 60; $i++) {
                try { if ((Invoke-RestMethod "$Base/actuator/health" -TimeoutSec 3).status -eq "UP") { break } } catch {}
                Start-Sleep 3
            }
            if (Wait-Profile "cust_123" 60) { Write-Host "Stack reset; seed personas are cached." -ForegroundColor Green }
            else { Write-Host "App is up but cust_123 is not cached yet; check the Debezium connector." -ForegroundColor Yellow }
        } finally { Pop-Location }
    }

    default { Get-Help $PSCommandPath -Examples | Out-String | Write-Host }
}
