# Scenario 1: live CDC mutation. A warehouse insert reaches the agent's Redis cache via Debezium and Kafka.
. "$PSScriptRoot\_common.ps1"
Require-Stack
Title "1. Live CDC mutation (Elena, cust_123): Postgres -> Debezium -> Kafka -> Redis"

$before = (Profile "cust_123").lastAppliedLsn
Say "Inserting a `$45 late fee into the warehouse (Postgres only; the app does not write Redis)..."
$m = Api POST "/api/demo/mutate" @{ customerId = "cust_123"; chargeType = "LATE_FEE"; amount = 45.00; description = "Late fee (demo)" }
Write-Host "  invoice $($m.invoiceId) inserted"
$sw = [Diagnostics.Stopwatch]::StartNew()
$synced = WaitFor { (Profile "cust_123").recentCharges | Where-Object { $_.invoiceId -eq $m.invoiceId } } 20
Check "CDC delivered $($m.invoiceId) to Redis in $($sw.ElapsedMilliseconds) ms" $synced
Check "lastAppliedLsn advanced" ((Profile "cust_123").lastAppliedLsn -gt $before)

Say "Consumer log lines for this event (FR-7.5):"
$lines = docker logs servicing-agent-app --since 1m 2>&1 | Select-String "CDC applied" | Select-Object -Last 4
$lines | ForEach-Object { Write-Host "  $($_.Line.Trim())" -ForegroundColor DarkGray }
Check "consumer logged 'CDC applied ... $($m.invoiceId)'" ([bool]($lines | Where-Object { $_.Line -match $m.invoiceId }))
Finish
