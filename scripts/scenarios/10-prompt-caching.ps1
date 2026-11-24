# Scenario 10: prompt caching (live LLM only). Byte-stable static prefix on every model call, even across customers; one call per turn in LLM mode, several in agent planner mode.
# Needs the app in live mode (scripts/demo-smoke-live.ps1 -KeepLive, or playbook section 5). Offline it reports SKIPPED.
. "$PSScriptRoot\_common.ps1"
Require-Stack
Title "10. Prompt caching (live LLM): one model call per turn, same static prefix every turn"

if (-not $env:CHAT_PACE_SECONDS) { $env:CHAT_PACE_SECONDS = "5" }   # stay under provider rate limits
$question = "What is the maximum late fee you can waive for an account like mine?"
$turns = @("cust_123", "cust_123", "cust_456")   # last turn: another customer, prefix must not change
$before = Api GET "/api/agent/reports/kpis"
$script:Agentic = $false; $hashes = @(); $tripCounts = @(); $prompt = @(); $cached = @(); $timeouts = 0

foreach ($c in $turns) {
    $r = Chat $c $question
    if (Trace-Has $r "PLANNER: DETERMINISTIC_REPLAY") {
        Write-Host "`n  SKIPPED: the app is in deterministic mode, so no model is called and there is nothing to cache." -ForegroundColor Yellow
        Write-Host "  Start live mode first: `$env:GEMINI_API_KEY='...'; scripts\demo-smoke-live.ps1 -KeepLive" -ForegroundColor Yellow
        exit 0
    }
    if (Trace-Has $r "PLANNER: LLM_AGENTIC") { $script:Agentic = $true }
    $tripCounts += @($r.agentReasoningTrace | Where-Object { $_.event -like "LLM_ROUND_TRIP*" }).Count
    if (Trace-Has $r "LLM_SYNTHESIS_TIMEOUT*") { $timeouts++ }
    $usage = ($r.agentReasoningTrace.event | Where-Object { $_ -like "LLM_USAGE (*" } | Select-Object -First 1)
    if ($usage -match "promptTokens=(\d+), cachedTokens=(\d+), prefix=([0-9a-f]+)") {
        $prompt += [int]$Matches[1]; $cached += [int]$Matches[2]; $hashes += $Matches[3]
        Write-Host "    $usage" -ForegroundColor Cyan
    } else {
        Write-Host "    (no usage: the model did not answer within the time limit, so this turn used the deterministic draft)" -ForegroundColor DarkGray
    }
}

Say "Results"
# Single-pass guarantee: never more than one model call per turn. A call still running at the 8 s limit is
# abandoned (LLM_SYNTHESIS_TIMEOUT), so it records no round trip, no usage and no llmCalls increment.
if ($script:Agentic) {
    # Agent planner mode: the model plans with several calls per turn, and every call resends the same static prefix.
    Check "every turn made at least one model call (agent planner, calls per turn: $($tripCounts -join ', '))" (@($tripCounts | Where-Object { $_ -lt 1 }).Count -eq 0)
} else {
    Check "no turn made more than one model call (LLM mode, calls per turn: $($tripCounts -join ', '))" (@($tripCounts | Where-Object { $_ -gt 1 }).Count -eq 0)
}
Check "model answered at least 2 of $($turns.Count) turns ($($hashes.Count); $timeouts hit the model time limit)" ($hashes.Count -ge 2)
Check "same static prefix on every answered turn and both customers ($(($hashes | Select-Object -Unique) -join ', '))" (@($hashes | Select-Object -Unique).Count -eq 1)
$after = Api GET "/api/agent/reports/kpis"
$delta = $after.llmCalls - $before.llmCalls
Check "KPI llmCalls +$delta matches the answered turns ($($hashes.Count))" ($delta -ge $hashes.Count)
Write-Host ("  promptTokens per turn: {0}; cachedTokens: {1}; cumulative hit rate {2}%" -f ($prompt -join ', '), ($cached -join ', '), $after.promptCacheHitRatePercent)
Write-Host "  Gemini's OpenAI-compatible endpoint does not report cached tokens, so cachedTokens stays 0 there; OpenAI reports them." -ForegroundColor DarkGray
Finish
