<#
.SYNOPSIS
  End-to-end smoke test with a live LLM writing the replies (Gemini Flash-Lite, single-pass).

.DESCRIPTION
  1. Recreates servicing-agent-app in live mode. The key is passed to docker compose for this one
     command only; it is never written to .env or any file.
  2. Runs scripts/demo-smoke.ps1 -Mode llm: the same functional checks as the deterministic run, with a
     16000 ms per-turn budget (15 s synthesis timeout plus tools, 2048 output tokens) and 8 s pacing between chat calls.
  3. Recreates the app in offline mode again, unless -KeepLive is given.

  Decisions, citations, escalations and the duplicate-ticket check are made in Java, so they must pass
  in both modes. If the provider is slow or overloaded, turns fall back to the deterministic draft;
  the script reports how many replies the model actually wrote.

.EXAMPLE
  $env:GEMINI_API_KEY = '...'          # AI Studio key; or set OPENAI_API_KEY
  powershell -ExecutionPolicy Bypass -File scripts/demo-smoke-live.ps1
  powershell -ExecutionPolicy Bypass -File scripts/demo-smoke-live.ps1 -KeepLive -Model gemini-3.1-flash-lite
#>
param(
    [string] $Model = "gemini-3.1-flash-lite",
    [int] $MaxLatencyMs = 16000,
    [int] $PaceSeconds = 8,
    [switch] $KeepLive
)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$base = "http://localhost:8080"

$key = if ($env:GEMINI_API_KEY) { $env:GEMINI_API_KEY } else { $env:OPENAI_API_KEY }
if (-not $key -or $key -like "placeholder*") {
    Write-Host "Set `$env:GEMINI_API_KEY (or OPENAI_API_KEY) in this shell first. It is not read from or written to any file." -ForegroundColor Red
    exit 2
}

$live = @{
    OPENAI_API_KEY = $key
    OPENAI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
    OPENAI_COMPLETIONS_PATH = "/chat/completions"
    OPENAI_MODEL = $Model
    AGENT_LLM_MODE = "llm"
    AGENT_LLM_ORCHESTRATION = "single-pass"
    AGENT_LLM_REASONING_EFFORT = "low"
    AGENT_LLM_SYNTHESIS_TIMEOUT_MS = "15000"     # live: room for Gemini thinking tokens under load
    AGENT_LLM_SYNTHESIS_MAX_TOKENS = "2048"     # live: 1024 was used up by thinking tokens on long answers
}

function Recreate-App([hashtable]$vars, [string]$expectLog) {
    Push-Location $root
    try {
        $saved = @{}
        foreach ($k in $vars.Keys) { $saved[$k] = [Environment]::GetEnvironmentVariable($k); [Environment]::SetEnvironmentVariable($k, $vars[$k]) }
        $ErrorActionPreference = "Continue"
        docker compose up -d --force-recreate servicing-agent-app 2>&1 | Out-Null
        $ErrorActionPreference = "Stop"
    } finally {
        foreach ($k in $vars.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k]) }   # key leaves this process env
        Pop-Location
    }
    for ($i = 0; $i -lt 60; $i++) {
        try { if ((Invoke-RestMethod "$base/actuator/health" -TimeoutSec 3).status -eq "UP") { break } } catch {}
        Start-Sleep 3
    }
    $mode = docker logs servicing-agent-app 2>&1 | Select-String "Agent planner mode" | Select-Object -Last 1
    Write-Host "  $($mode.Line.Trim() -replace '^.*Agent planner mode', 'Agent planner mode')" -ForegroundColor DarkGray
    return [bool]($mode -and $mode.Line -match [regex]::Escape($expectLog))
}

# Offline: clear every live setting so compose falls back to its defaults (placeholder key -> offline planner).
$offline = @{}; foreach ($k in $live.Keys) { $offline[$k] = $null }

Write-Host "Switching the app to live mode ($Model, single-pass)..." -ForegroundColor Cyan
if (-not (Recreate-App $live "LLM single-pass")) {
    Write-Host "App did not start in live mode; restoring offline mode." -ForegroundColor Red
    $null = Recreate-App $offline "DETERMINISTIC_REPLAY"
    exit 1
}

$code = 1
try {
    $prevPace = $env:CHAT_PACE_SECONDS
    $env:CHAT_PACE_SECONDS = "$PaceSeconds"
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "demo-smoke.ps1") -Mode llm -MaxLatencyMs $MaxLatencyMs
    $code = $LASTEXITCODE
} finally {
    $env:CHAT_PACE_SECONDS = $prevPace
    if (-not $KeepLive) {
        Write-Host "`nRestoring offline mode..." -ForegroundColor Cyan
        $null = Recreate-App $offline "DETERMINISTIC_REPLAY"
    } else {
        Write-Host "`n-KeepLive: the app stays in live mode. Run this script without -KeepLive, or recreate the app, to go back offline." -ForegroundColor Yellow
    }
}
exit $code
