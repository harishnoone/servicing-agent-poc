# Scenario 12: a chat customer asks about their ticket. Status, SLA time left or breached; a breached ticket escalates.
. "$PSScriptRoot\_common.ps1"
Require-Stack
Title "12. Ticket status inquiry (Elena cust_123: within SLA; Marcus cust_456: past SLA)"

Say "Elena asks about her ticket. It is open and within SLA, so the agent answers without a handoff:"
$r = Chat "cust_123" "Hi, what's the status of my ticket?"
Check "agent looked up her tickets" ((Trace-Has $r "TICKET_STATUS: *") -or (Trace-Has $r "TOOL_CALL: getCustomerState*"))
Check "reply names TKT-7721 with its status" (($r.finalResponse -match "TKT-7721") -and ($r.finalResponse -match "OPEN"))
Check "reply states the SLA" ($r.finalResponse -match "SLA")
Check "resolved without a handoff" ($r.kpiImpact.deflectionStatus -eq "RESOLVED_AUTONOMOUSLY")
CheckLatency "Elena turn" $r

Say "Marcus asks about a specific ticket by id. It is past its SLA, so the agent escalates with full context:"
$r = Chat "cust_456" "Any news on TKT-8841?"
Check "agent looked up TKT-8841" ((Trace-Has $r "TICKET_STATUS: * matching TKT-8841") -or ((Trace-Has $r "TOOL_CALL: getCustomerState*") -and ($r.finalResponse -match "TKT-8841")))
Check "reply says the SLA was breached" ($r.finalResponse -match "breached")
Check "escalated with SLA_BREACH on the handoff card" ($r.kpiImpact.deflectionStatus -eq "ESCALATED" -and $r.handoffCard.detectedSentiment -eq "SLA_BREACH")
CheckLatency "Marcus turn" $r
Say "Marcus asks vaguely. The breached ticket must still escalate in every mode (Java enforces it):"
$r = Chat "cust_456" "What is my status?"
Check "vague question still escalates the breached ticket" ($r.kpiImpact.deflectionStatus -eq "ESCALATED")
Finish
