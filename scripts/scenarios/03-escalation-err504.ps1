# Scenario 3: conflicting knowledge + SLA breach. Canonical 2026 fix beats the superseded 2024 case; SLA breach escalates.
. "$PSScriptRoot\_common.ps1"
Require-Stack
Title "3. ERR-504-GW conflict resolution and SLA escalation (Marcus, cust_456)"

$r = Chat "cust_456" "My gateway is throwing ERR-504-GW, what should I do?"
Check "ESCALATED" ($r.kpiImpact.deflectionStatus -eq "ESCALATED") $r.kpiImpact.deflectionStatus
Check "cites troubleshooting_guide.md#sec-1.1 (2026 fix)" ($r.citations -contains "troubleshooting_guide.md#sec-1.1")
Check "2024 firmware case called out as superseded" ($r.finalResponse -match "superseded")
Check "handoff card: TKT-8841 + SLA_BREACH" (($r.handoffCard.openTickets.ticketId -contains "TKT-8841") -and $r.handoffCard.detectedSentiment -eq "SLA_BREACH")
Finish
