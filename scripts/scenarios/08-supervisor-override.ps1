# Scenario 8: human-in-the-loop override with an append-only audit trail. Changes demo data; reset after.
. "$PSScriptRoot\_common.ps1"
Require-Stack
Title "8. Supervisor override + append-only audit (Marcus, cust_456)"

$o = Api POST "/api/admin/overrides/waiver" @{ customerId = "cust_456"; invoiceId = "INV-2025-456-001"; amount = 35.00
    justification = "Goodwill credit during ERR-504-GW outage" }
Check "override applied, audit id $($o.auditId)" ($o.status -eq "APPLIED" -and $o.auditId)
$audit = Sql "SELECT action||'|'||supervisor_sub FROM audit_log WHERE audit_id=$($o.auditId)"
Check "audit row written ($audit)" ($audit -match "^WAIVER_OVERRIDE\|")
$ErrorActionPreference = "Continue"
$del = docker exec warehouse-db psql -U warehouse_admin -d enterprise_warehouse -tAc "DELETE FROM audit_log WHERE audit_id=$($o.auditId)" 2>&1 | Out-String
$ErrorActionPreference = "Stop"
Check "audit log rejects DELETE" ("$del" -match "append-only")
Check "invoice WAIVED reached Redis via CDC" (WaitFor { ((Profile "cust_456").recentCharges | Where-Object { $_.invoiceId -eq "INV-2025-456-001" }).status -eq "WAIVED" } 20)
Finish
