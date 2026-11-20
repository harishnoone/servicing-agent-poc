# Scenario 5: no duplicate tickets. The agent recognises an open ticket for the same problem (FR-6.3),
# and ticket creation returns the existing ticket instead of inserting a duplicate (FR-6.4).
. "$PSScriptRoot\_common.ps1"
Require-Stack
Title "5. Same customer, same problem: follow-up on the open ticket, no duplicate (Marcus, cust_456)"

Say "Marcus already has TKT-8841 open for ERR-504-GW. He writes in again about the same problem:"
$r = Chat "cust_456" "Hi again, the gateway is still throwing ERR-504-GW, any update?"
Check "findMatchingOpenTicket -> EXISTING_TICKET TKT-8841 (ERROR_CODE)" (Trace-Has $r "TOOL_CALL: findMatchingOpenTicket -> EXISTING_TICKET TKT-8841 (ERROR_CODE)")
Check "reply treats it as a follow-up on TKT-8841" ($r.finalResponse -match "TKT-8841")

Say "Now try to open a second ticket for the same problem (as a CRM form or integration would):"
$countSql = "SELECT COUNT(*) FROM support_tickets WHERE customer_id='cust_456'"
$before = [int](Sql $countSql)
$t = Api POST "/api/demo/tickets" @{ customerId = "cust_456"; category = "GATEWAY_OUTAGE"
    subject = "Gateway still returning ERR-504-GW"; status = "OPEN"; slaHoursFromNow = 24 }
Write-Host "  -> $($t.message)" -ForegroundColor DarkGray
Check "returned existing TKT-8841 with duplicate=true ($($t.matchReason))" ($t.duplicate -and $t.ticketId -eq "TKT-8841")
$after = [int](Sql $countSql)
Check "no new warehouse row (tickets for cust_456: $before -> $after)" ($after -eq $before)

Say "Contrast: a policy question about outages is a new request, not a follow-up:"
$r = Chat "cust_456" "What SLA credit do I get for a P1 outage longer than 4 hours?"
Check "policy question is not matched to the open ticket" (-not ($r.finalResponse -match "already have an open ticket"))
Finish
