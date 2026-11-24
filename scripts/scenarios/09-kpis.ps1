# Scenario 9: executive KPI report for everything run so far.
. "$PSScriptRoot\_common.ps1"
Require-Stack
Title "9. KPI report"

$k = Api GET "/api/agent/reports/kpis"
$k | Format-List totalSessions, autonomousDeflections, escalations, deflectionRatePercent, totalAhtMinutesSaved,
    policyComplianceScore, cacheMissFallbacks, dltMessageDepth, llmCalls, promptTokens, llmEstimatedCostUsd, llmCostIfPrefixCachedUsd, projectedCachingSavingPercent | Out-Host
Check "report returned" ($null -ne $k.totalSessions)
Finish
