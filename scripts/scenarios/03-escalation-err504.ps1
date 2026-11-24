# Scenario 3: conflicting knowledge + SLA breach. Canonical 2026 fix beats the superseded 2024 case; SLA breach escalates.
. "$PSScriptRoot\_common.ps1"
Require-Stack
Title "3. ERR-504-GW conflict resolution and SLA escalation (Marcus, cust_456)"

$r = Chat "cust_456" "My gateway is throwing ERR-504-GW, what should I do?"
Check "ESCALATED" ($r.kpiImpact.deflectionStatus -eq "ESCALATED") $r.kpiImpact.deflectionStatus
Check "cites troubleshooting_guide.md#sec-1.1 (2026 fix)" ($r.citations -contains "troubleshooting_guide.md#sec-1.1")
Check "2024 firmware case called out as superseded" ($r.finalResponse -match "superseded")
Check "handoff card: TKT-8841 + SLA_BREACH" (($r.handoffCard.openTickets.ticketId -contains "TKT-8841") -and $r.handoffCard.detectedSentiment -eq "SLA_BREACH")

Say "The escalation is an action too: published to agent.actions.escalation, applied by the system of record:"
Check "escalation action published (ACTION_PUBLISHED: escalation)" (Trace-Has $r "ACTION_PUBLISHED: escalation *")
$actionId = (($r.agentReasoningTrace.event | Where-Object { $_ -like "ACTION_PUBLISHED: escalation *" } | Select-Object -First 1) -split ' ')[2]
$script:handoff = $null
$applied = WaitFor { $script:handoff = Sql "SELECT action||'|'||customer_id FROM audit_log WHERE justification LIKE 'action=$actionId;%' LIMIT 1"; [bool]"$script:handoff".Trim() } 20
Check "system of record applied $actionId ($($script:handoff))" ($applied -and "$script:handoff".Trim() -eq "ESCALATION_HANDOFF|cust_456")
docker logs servicing-agent-app --since 2m 2>&1 | Select-String "Action produced: topic=agent.actions.escalation" | Select-Object -Last 1 | ForEach-Object { Write-Host "  $($_.Line.Trim() -replace '^.*?: ','')" -ForegroundColor DarkGray }
Finish
