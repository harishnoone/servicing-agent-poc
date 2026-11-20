# Scenario 6: dormant account. Redis miss -> bounded warehouse lookup -> async re-hydration.
. "$PSScriptRoot\_common.ps1"
Require-Stack
Title "6. Dormant account / cache miss (Elena, cust_123)"

Say "Evicting Elena's profile from Redis to simulate a dormant account..."
$null = Api DELETE "/api/demo/cache/cust_123"
$r = Chat "cust_123" "What is my latest charge?"
Check "trace shows CACHE_MISS -> WAREHOUSE_FALLBACK" (Trace-Has $r "*CACHE_MISS -> WAREHOUSE_FALLBACK*")
Check "turn latency $($r.kpiImpact.totalTurnLatencyMs) ms < 1500" ($r.kpiImpact.totalTurnLatencyMs -lt 1500)
Check "Redis re-hydrated in the background" (WaitFor { Profile "cust_123" } 5)
Finish
