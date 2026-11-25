# Scenario 13: a conversational customer. Follow-ups keep the topic, asking for a person escalates, thank-you closes.
. "$PSScriptRoot\_common.ps1"
Require-Stack
Title "13. Multi-turn conversation (Jordan cust_789): policy back-and-forth, then 'let me talk to a person'"

# A fresh conversation id per run; the agent keeps the last 3 exchanges per customer + conversation id (FR-6.6).
$conv = "conv-" + [guid]::NewGuid().ToString().Substring(0, 8)
Say "Conversation $conv. Jordan (Standard tier) asks for a waiver and is turned down:"
$r = Chat "cust_789" "Can you waive my late fee?" $conv
Check "denied by the rule engine (TIER_INELIGIBLE)" (Trace-Has $r "TOOL_CALL: evaluateWaiverRules -> TIER_INELIGIBLE")
Check "not escalated yet" ($r.kpiImpact.deflectionStatus -eq "RESOLVED_AUTONOMOUSLY")
CheckLatency "turn 1" $r

Say "A follow-up with no topic of its own. The agent remembers the waiver conversation:"
$r = Chat "cust_789" "Why not? Can you make an exception?" $conv
Check "earlier exchange loaded" (Trace-Has $r "CONVERSATION_HISTORY_LOADED (1 earlier exchange)")
Check "reply stays on the waiver (not a generic account summary)" (($r.finalResponse -match "waiv|exception|tier|supervisor|policy") -and ($r.finalResponse -notmatch "^Here is what I see on your account"))
Check "no approval invented" ($r.finalResponse -notmatch "(?i)i have approved|has been approved|you qualify")
CheckLatency "turn 2" $r

Say "Jordan is not happy and asks for a person. This escalates in every mode (Java checks the request):"
$r = Chat "cust_789" "That doesn't help me. Can I please speak to a real person?" $conv
Check "escalated" ($r.kpiImpact.deflectionStatus -eq "ESCALATED")
Check "handoff card says the customer asked for a person" ($r.handoffCard.detectedSentiment -eq "CUSTOMER_REQUESTED_HUMAN")
Check "handoff card carries the earlier conversation or the waiver topic" ((($r.handoffCard.attemptedSteps -join " ") -match "waive") -or ($r.handoffCard.classifiedIntent -match "WAIVER|BILLING"))
CheckLatency "turn 3" $r

Say "Jordan says thank you. The agent closes politely instead of repeating its last answer (no tools, no model call):"
$r = Chat "cust_789" "Thank you!" $conv
Check "acknowledged as a closing" (Trace-Has $r "CONVERSATION_CLOSING*")
Check "reply is a short thank-you that mentions the hand-off" (($r.finalResponse -match "^You're welcome") -and ($r.finalResponse -match "Tier-2"))
Check "no tools re-run" (-not (Trace-Has $r "TOOL_CALL*"))
CheckLatency "turn 4" $r

Say "Without a conversation id each turn stands alone (no history is loaded):"
$r = Chat "cust_789" "Why not?"
Check "no history without a conversation id" (-not (Trace-Has $r "CONVERSATION_HISTORY_LOADED*"))
Finish
