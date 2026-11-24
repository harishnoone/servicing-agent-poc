# Scenario 8: supervisor override as a Kafka action. The admin API only publishes; the system of record applies it once.
. "$PSScriptRoot\_common.ps1"
Require-Stack
Title "8. Supervisor override -> Kafka action -> system of record -> CDC (Marcus, cust_456)"

Say "Supervisor approves an out-of-policy waiver. The admin API validates with reads and publishes an action; it does not write:"
$body = @{ customerId = "cust_456"; invoiceId = "INV-2025-456-001"; amount = 35.00; justification = "Goodwill credit during ERR-504-GW outage" }
$o = Api POST "/api/admin/overrides/waiver" $body
Write-Host "  -> status $($o.status), action $($o.actionId)" -ForegroundColor DarkGray
Check "override ACCEPTED as action $($o.actionId)" ($o.status -eq "ACCEPTED" -and $o.actionId)

$script:auditId = $null
$applied = WaitFor { $script:auditId = Sql "SELECT audit_id FROM audit_log WHERE justification LIKE 'action=$($o.actionId);%' ORDER BY audit_id LIMIT 1"; [bool]"$script:auditId".Trim() } 20
Check "system of record consumed agent.actions.waiver and applied it (audit id $($script:auditId))" $applied
$audit = Sql "SELECT action||'|'||supervisor_sub FROM audit_log WHERE audit_id=$($script:auditId)"
Check "audit row written by the system of record ($audit)" ($audit -match "^WAIVER_OVERRIDE\|")

Say "Same override again (a double click or a retry): same action id, applied once:"
$o2 = Api POST "/api/admin/overrides/waiver" $body
Start-Sleep 3
$rows = [int](Sql "SELECT COUNT(*) FROM audit_log WHERE justification LIKE 'action=$($o.actionId);%'")
Check "repeat returns the same action id ($($o2.actionId))" ($o2.actionId -eq $o.actionId)
Check "still exactly one audit row for this action ($rows)" ($rows -eq 1)

$ErrorActionPreference = "Continue"
$del = docker exec warehouse-db psql -U warehouse_admin -d enterprise_warehouse -tAc "DELETE FROM audit_log WHERE audit_id=$($script:auditId)" 2>&1 | Out-String
$ErrorActionPreference = "Stop"
Check "audit log rejects DELETE" ("$del" -match "append-only")
Check "invoice WAIVED reached Redis via CDC" (WaitFor { ((Profile "cust_456").recentCharges | Where-Object { $_.invoiceId -eq "INV-2025-456-001" }).status -eq "WAIVED" } 20)

Say "Produced actions (app log):"
docker logs servicing-agent-app --since 2m 2>&1 | Select-String "Action produced|System of record" | Select-Object -Last 3 | ForEach-Object { Write-Host "  $($_.Line.Trim() -replace '^.*?: ','')" -ForegroundColor DarkGray }
Finish
