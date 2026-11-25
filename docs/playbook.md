# Demo & Operations Playbook

Runbook for presenting the Servicing Agent POC and for handling the things that go wrong. Setup and configuration are in `../README.md`. Design decisions and measured results are in `design.md`.

All commands are PowerShell, run from the repository root.

---

## 1. Before the demo (T-15 minutes)

1. Start Docker Desktop and wait until `docker info` succeeds.
2. Start from clean data and load the extra test customers:
   ```powershell
   .\scripts\demo.ps1 reset      # docker compose down -v + up, waits until personas are cached
   .\scripts\demo.ps1 seed       # 8 more customers, one per outcome (see README)
   ```
   After code changes, run `docker compose up --build -d` once first.
3. Wait for `Invoke-RestMethod http://localhost:8080/actuator/health` to show `status = UP`, with `db` and `redis` both `UP`.
4. Confirm the logs:
   ```powershell
   docker logs servicing-agent-app 2>&1 | Select-String "planner mode|Debezium connector"
   ```
   Expect `Agent planner mode: ...` and `Debezium connector 'warehouse-postgres-connector' registered`.
   For a live-model demo, start the app as in section 5 and expect `Agent planner mode: LLM single-pass (Java tool plan + one model call)` and `Gemini endpoint detected`.
   To follow turns live during the demo: `docker logs -f servicing-agent-app 2>&1 | Select-String "Turn |LLM round trip|CDC applied|Duplicate ticket"`. Add `"Action produced|System of record"` to see every action the agent publishes and the system of record applies. In live mode each turn also logs `Turn cost:` with tokens, estimated cost and the projected prompt-caching saving. Every line carries `[sessionId customerId]`.
5. Live mode only: run `.\scripts\demo.ps1 caching cust_123 2` once. Each turn must show exactly one `LLM_ROUND_TRIP #1 (HTTP 200)` and `LLM_SYNTHESIS_ACCEPTED`. If you see 429 or 402, fix the key before the demo (section 5).
6. Rehearse once: `.\scripts\demo.ps1 scenarios` prints every seeded question with its outcome, tool trace and citations. For a pass/fail check, run `powershell -ExecutionPolicy Bypass -File scripts/demo-smoke.ps1` (deterministic, `ALL CHECKS PASSED`). If you will present in live mode, also run `scripts/demo-smoke-live.ps1` (section 5). **Then reset and seed again** (step 2): these scripts change demo data.
7. Open `http://localhost:8080/swagger-ui.html` and keep a terminal ready for the Redis checks.

Personas after a reset:

| Customer | Who | State | Demo role |
|---|---|---|---|
| `cust_123` | Elena Vance | Gold, 36 months, 0 waivers | Waiver approved autonomously |
| `cust_456` | Marcus Sterling | Enterprise, 1 waiver used, TKT-8841 4h past SLA (ERR-504-GW) | Conflict resolution and escalation |
| `cust_789` | Jordan Blake | Standard, 6 months, $25 late fee | Clean denial |

After `seed`, eight more customers are available for audience questions (`cust_222`, `321`, `654`, `444`, `555`, `987`, `333`, `111`); the README lists the expected outcome for each.

**Quick inserts during Q&A:** if the panel asks *"what if..."*, build the case live, e.g. `.\scripts\demo.ps1 customer cust_q1 "Panel Guest" Gold 14 299`, then `charge cust_q1 LATE_FEE 49.99`, then `chat cust_q1 "Can you waive my late fee?"`. Each command waits until CDC has delivered the change.

---

## 2. Demo script

The flow of every scenario below is drawn in [`scenarios.mmd`](scenarios.mmd), useful as a backup slide.

Every step below is also a standalone script in `scripts/scenarios/`, so you can pick steps during the demo: `.\scripts\scenarios\run.ps1` shows a menu, `run.ps1 5` runs one, `run.ps1 2 3 5` runs several in order. Each script prints the trace and reply and ends with `SCENARIO PASSED` or the failed checks. `scripts/demo-smoke.ps1` stays the full end-to-end run. Scenario 8 (supervisor override) changes data; run it last or reset after.

| # | Script | Section |
|---|---|---|
| 1 | `01-cdc-mutation.ps1` | Step 1 |
| 2 | `02-autonomous-waiver.ps1` | Step 2 |
| 3 | `03-escalation-err504.ps1` | Step 3 |
| 4 | `04-clean-denial.ps1` | Step 3 follow-up and Jordan |
| 5 | `05-duplicate-ticket.ps1` | Step 3b |
| 6 | `06-cache-miss.ps1` | Optional: dormant account |
| 7 | `07-poison-pill-dlt.ps1` | Optional: poison pill |
| 8 | `08-supervisor-override.ps1` | Optional: supervisor override |
| 9 | `09-kpis.ps1` | Step 4 |
| 10 | `10-prompt-caching.ps1` | Optional: prompt caching (live mode only; SKIPPED offline) |
| 11 | `11-billing-investigation.ps1` | Step 3c: multi-step investigation (self-seeds Riley) |
| 12 | `12-ticket-status.ps1` | Step 3d: ticket status inquiry |
| 13 | `13-conversation.ps1` | Step 3e: multi-turn conversation, then "let me talk to a person" |

### Step 1: Live CDC mutation (Elena)

Swagger: **Demo → POST /api/demo/mutate**
```json
{ "customerId": "cust_123", "chargeType": "LATE_FEE", "amount": 45.00, "description": "Late payment fee" }
```
Show that the charge reached Redis without the app writing to Redis directly:
```powershell
docker exec redis redis-cli GET customer:profile:cust_123
```
Point out the new invoice in `recentCharges` and the higher `lastAppliedLsn`. Optional: show the raw event with
`docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic db.public.billing_ledger --from-beginning --max-messages 5`.

### Step 2: Autonomous waiver (Elena), panel drives

**Agent → POST /api/agent/chat**
```json
{ "customerId": "cust_123", "message": "Why is my bill $45 higher, and can you waive it?" }
```
Walk through the `AgentTurnReport`:
- `agentReasoningTrace`: `PII_SANITIZATION_PASSED`, the planner and snapshot markers, then `getCustomerState (REDIS_HIT)`, `searchPoliciesAndCases (confidence=…)`, `evaluateWaiverRules -> APPROVED`, `escalateWithSummary -> NO_ESCALATION_NEEDED`, each with its own `latencyMs`.
- `citations`: `billing_waivers_policy.md#sec-4.2`.
- `kpiImpact.deflectionStatus`: `RESOLVED_AUTONOMOUSLY`; `totalTurnLatencyMs` well under 1500.
- Live mode adds `PLANNER: LLM_SINGLE_PASS`, `DETERMINISTIC_PLAN_EXECUTED`, one `LLM_ROUND_TRIP #1` (about 1.5 to 2.5 s on Flash-Lite) and `LLM_SYNTHESIS_ACCEPTED`. The tools ran before the model was called, so the model only wrote the wording.
- The approval came from Java. Ask the panel to try `"... waive $60"` and watch `FEE_TOO_HIGH`.

### Step 3: Conflict resolution and escalation (Marcus)

```json
{ "customerId": "cust_456", "message": "My gateway is throwing ERR-504-GW, what should I do?" }
```
- The answer cites `[troubleshooting_guide.md#sec-1.1]` (2026 certificate rotation fix) and says the 2024 firmware-downgrade case `CASE-2024-0317` is **superseded**.
- `deflectionStatus: ESCALATED`, because TKT-8841 is past its SLA.
- `handoffCard`: tier, tenure, MRR, open tickets, `detectedSentiment: SLA_BREACH`, urgency, the tools executed in order, policy citations and the recommended next action. This is what saves Tier-2 handle time.

Follow-up: `"Please waive the $35 late fee"` → `WAIVER_LIMIT_REACHED` (Marcus already used his one waiver).

**Actions to point out (FR-9):** Step 2's approval publishes `agent.actions.waiver` and the system of record really waives the invoice (it arrives back in Redis as `WAIVED`); Step 3's escalation publishes `agent.actions.escalation` and lands in `audit_log` as `ESCALATION_HANDOFF`. Scenarios 2, 3 and 8 check each action end to end; tail `Action produced|System of record` in the logs to show them live. Because approvals change data, scenario 2 and `demo-smoke.ps1` reset Elena's waiver count first.

### Step 3b: Same problem again, no duplicate ticket (Marcus)

Script: `.\scripts\scenarios\run.ps1 5`. Marcus already has TKT-8841 open for ERR-504-GW.
1. **Agent → POST /api/agent/chat**: `{"customerId":"cust_456","message":"Hi again, the gateway is still throwing ERR-504-GW, any update?"}`. The trace shows `TOOL_CALL: findMatchingOpenTicket -> EXISTING_TICKET TKT-8841 (ERROR_CODE)`, and the reply opens with "You already have an open ticket for this: TKT-8841 ... no need to open a new one".
2. **Demo → POST /api/demo/tickets** with the same problem: `{"customerId":"cust_456","category":"GATEWAY_OUTAGE","subject":"Gateway still returning ERR-504-GW","status":"OPEN","slaHoursFromNow":24}`. The response is `ticketId: TKT-8841, duplicate: true, matchReason: ERROR_CODE`, and no row is inserted. `docker logs servicing-agent-app` shows `Duplicate ticket prevented: existing=TKT-8841 reason=ERROR_CODE`.
3. Contrast: `"What SLA credit do I get for a P1 outage longer than 4 hours?"` is a policy question, so it gets a normal policy answer, not a follow-up.

Point out: the check is deterministic Java (same input, same answer, with a stated reason), the creation check reads the warehouse rather than Redis, and it locks the customer row so two concurrent requests cannot both create a ticket.

### Step 3c: Multi-step investigation (Riley)

Script: `.\scripts\scenarios\run.ps1 11` (creates Riley through CDC if needed). Ask as `cust_246`: `"Why is my bill so high this month?"`. The question names no fee and no error code, so the agent has to investigate. The trace shows five steps: `INVESTIGATE 1/5 billing` (`` overage + `` late fee = ``), `2/5 tickets` (the open P1 outage ticket), `checkWaiverEligibility -> APPROVED (not applied)`, `4/5 policy` (SLA credit under `sla_credit_policy.md#sec-4.1`) and `5/5 VERIFIED`. Point out: the late fee is reported as waivable but not waived (no audit row; the customer has to ask), and the answer is checked against the tools before it is sent. In live mode the LLM rewrite is rejected if it adds an amount that is not in the verified draft (`UNVERIFIED_AMOUNT`).

### Step 3d: Ticket status inquiry (Elena, Marcus)

Script: `.\scripts\scenarios\run.ps1 12`. Elena asks `"Hi, what's the status of my ticket?"`: the reply lists TKT-7721 with its subject, status and SLA time left, and the turn resolves. Marcus asks `"Any news on TKT-8841?"`: the reply says the SLA was breached 4 hours ago and the turn escalates with the handoff card. Phrasing matters in deterministic mode: "status of my ticket", "my ticket", "any update on" or a ticket id give the ticket-status answer; a vague "Any news?" or "What is my status?" gives the general account summary, which also names the ticket. Every phrasing escalates a breached ticket. In agent planner mode the model handles all phrasings and Java enforces the escalation check if the model skips it (`AGENTIC_ESCALATION_ENFORCED`).

### Step 3e: A conversational customer (Jordan)

Script: `.\scripts\scenarios\run.ps1 13`. Turns that share a `conversationId` remember the last 3 exchanges (FR-6.6). Jordan asks `"Can you waive my late fee?"` and is denied (`TIER_INELIGIBLE`, offer of a supervisor review). Then `"Why not? Can you make an exception?"`: the trace shows `CONVERSATION_HISTORY_LOADED (1 earlier exchange)` and the reply stays on the waiver. Then `"That doesn't help me. Can I please speak to a real person?"`: the turn escalates with `CUSTOMER_REQUESTED_HUMAN` on the handoff card, and the card's attempted steps include the earlier messages, so the Tier-2 agent does not ask again. A plain "yes" after the supervisor offer also escalates. In Swagger, either set `conversationId` yourself on the first turn (e.g. `demo-jordan-1`), or leave it out and copy the `sessionId` from the first response into `conversationId` on the next request. A request without `conversationId` always starts a new conversation.

Measured on 2026-11-24 (Gemini heavily loaded): deterministic 4 to 9 ms per turn, all checks passed. Single-pass passed every check, but every model call hit 503 or the 15 s cap, so the replies were the deterministic draft (about 15 s per turn). Agent planner passed every behaviour check; the model used the history (it escalated the "exception" request on its own), turns took 30 to 111 s, and the slowest turn exceeded the 60 s SLA because four calls of 25 to 30 s ran in sequence.

**If the panel asks "what if the customer gets conversational?":** follow-ups keep the topic for the last 3 exchanges; asking for a person always reaches Tier-2 with the conversation attached, in every mode, because Java checks the request and does not rely on the model; repeat escalations reuse the same action, so there is no duplicate handoff. Long-term case memory is the next step.

### Step 4: KPIs

**Agent → GET /api/agent/reports/kpis**: sessions, deflection rate, AHT minutes saved, compliance score, cache-miss fallbacks and DLT depth. In live mode also `llmCalls`, `promptTokens`, `cachedPromptTokens`, `promptCacheHitRatePercent` and the cost fields `llmEstimatedCostUsd`, `llmCostIfPrefixCachedUsd`, `projectedCachingSavingPercent` (README "Cost and prompt-caching telemetry") (cached tokens stay 0 on Gemini; see section 5).

### Optional segments

**Dormant account / cache miss**
1. **Demo → DELETE /api/demo/cache/{customerId}** with `cust_123`.
2. Chat as `cust_123`: `"What is my latest charge?"`. The trace shows `getCustomerState (CACHE_MISS -> WAREHOUSE_FALLBACK)`, latency is still low, and `docker exec redis redis-cli EXISTS customer:profile:cust_123` returns `1` again a moment later.

**Prompt caching (live mode only)**
```powershell
.\scripts\demo.ps1 caching cust_123 3     # same question 3 times, 5 s apart
```
Point out: one LLM round trip per turn, the same `prefix=<hash>` on every turn (the static prefix never changes), and about 1,390 prompt tokens per turn, of which about 900 are the cacheable prefix. Say plainly that Gemini's OpenAI-compatible endpoint does not report cached tokens, so the hit rate shows 0% there. On OpenAI the same command shows `cachedTokens` per turn. In the offline planner the command stops after turn 1 and says there is nothing to cache.

**Supervisor override (HITL)**: **Admin → POST /api/admin/overrides/waiver**
```json
{ "customerId": "cust_456", "invoiceId": "INV-2025-456-001", "amount": 35.00,
  "justification": "Goodwill credit during ERR-504-GW outage" }
```
Show the audit row, then show that it cannot be changed:
```powershell
docker exec warehouse-db psql -U warehouse_admin -d enterprise_warehouse -c "SELECT * FROM audit_log ORDER BY audit_id DESC LIMIT 3"
docker exec warehouse-db psql -U warehouse_admin -d enterprise_warehouse -c "DELETE FROM audit_log"   # ERROR: audit_log is append-only
```

**Poison pill and DLT**
```powershell
./scripts/demo.ps1 poison        # or POST /api/demo/poison-pill {"table":"customers"}
```
About 8 seconds later (retries after 1s, 2s and 4s), **Admin → GET /api/admin/kafka/dlt** shows depth 1, with the exception class and original topic. The KPI `dltMessageDepth` is 1. Other CDC events kept flowing the whole time. **POST /api/admin/kafka/dlt/replay** with `{"topic":"db.public.customers.DLT"}` republishes it. Because the payload is still invalid, it lands on the DLT again, which shows that replay belongs *after* fixing the root cause.

---

## 3. Resetting between runs

| Need | Command |
|---|---|
| Everything back to seed data | `.\scripts\demo.ps1 reset` then `.\scripts\demo.ps1 seed` |
| Only the KPI counters | `docker compose restart servicing-agent-app` |
| Undo demo charges or overrides | Full reset (rows are not deleted by any endpoint) |
| Done for the day | `docker compose stop` (keeps data; `docker compose start` resumes) |
| Remove everything | `docker compose down -v` |

---

## 4. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `docker` hangs or "Docker Desktop is unable to start" | Docker Desktop/WSL not running | Start Docker Desktop; on WSL errors run `wsl --update` and restart it |
| App health `DOWN` / container restarting | Postgres or Redis not ready | `docker compose ps`; `docker logs servicing-agent-app` |
| Chat returns `PARTIAL` and `customerSnapshot: null` | Customer exists in neither Redis nor the warehouse | Check the id; check that `init.sql` ran (fresh volume) |
| Mutations do not reach Redis | Connector not registered or failed | `Invoke-RestMethod http://localhost:8083/connectors/warehouse-postgres-connector/status`; re-register (below) |
| Old persona values (e.g. Marcus has 0 waivers) | Postgres volume predates the current `init.sql` | `docker compose down -v` |
| Planner mode is `LLM` but you expected offline | `OPENAI_API_KEY` set in `.env` or the shell | Clear it or set `AGENT_LLM_MODE=replay` |
| Every Marcus turn escalates | By design: TKT-8841 is past its SLA | Expected |
| `docker logs` shows only startup lines | Image built before `logback-spring.xml` and the per-turn logging | `docker compose up --build -d` |
| Turn shows `HTTP 429` in the logs, or the script says "LLM provider rejected the turn" | Rate limit (requests per minute) or free-tier daily quota (about 20 requests per model per day; 0 for Pro) | Set `$env:CHAT_PACE_SECONDS = 8`; if it persists, the daily quota is used up: switch model or enable billing |
| `HTTP 402 ... prepayment credits are depleted` | Gemini prepaid balance is 0 | Add credits in AI Studio, or switch to offline (`AGENT_LLM_MODE=replay`) |
| `HTTP 404 ... no longer available to new users` | Retired model id (`gemini-1.5-pro`, `gemini-2.x`) | Use `gemini-3.1-flash-lite` |
| `HTTP 400 ... missing a thought_signature` | Agentic mode on Gemini without the interceptor (base URL not Gemini's) | Check `OPENAI_BASE_URL`; or use `AGENT_LLM_ORCHESTRATION=single-pass` |
| Trace shows `LLM_SYNTHESIS_TIMEOUT` or `LLM_SYNTHESIS_FALLBACK` | Model slower than 8 s (Pro models) or provider error | The turn still answers from the deterministic draft. Use Flash-Lite, or raise `AGENT_LLM_SYNTHESIS_TIMEOUT_MS` |
| Trace shows `LLM_SYNTHESIS_REJECTED (CITATIONS_DROPPED)` | The model reply left out the `[doc_id]` citations | Expected safety net: the deterministic draft is returned. Mention it if the panel asks |
| `LLM_SYNTHESIS_TRUNCATED` | Thinking tokens used up `max-tokens` | Raise `AGENT_LLM_SYNTHESIS_MAX_TOKENS` or lower `AGENT_LLM_REASONING_EFFORT` |
| Agent planner turn hangs after one `LLM round trip` line | A provider call stalled (seen on Gemini under load). Before 2026-11-23 the model HTTP client had no read timeout | Fixed: read timeout per model call (now 30 s, `AGENT_LLM_HTTP_READ_TIMEOUT_SECONDS`); the turn then returns the deterministic answer (`AGENTIC_FALLBACK` in the trace). Not caused by prompt caching |
| Log shows `HTTP 503 ... high demand` and `LLM circuit OPEN` | Gemini overloaded (provider side, not our code) | Expected: after 3 failed calls the circuit opens for 60 s and turns return the deterministic answer in about 1.5 s (`LLM_CIRCUIT_OPEN` in the trace). It closes on the first successful trial call. For the demo, lead with deterministic mode; set `AGENT_LLM_FALLBACK_MODELS` to a second model, or switch `OPENAI_MODEL`, if 503s persist |
| Follow-up answered as if it were a new conversation | No `conversationId` in the request, a different customer id, or more than 30 minutes since the last turn | Send the previous response's `sessionId` as `conversationId`; the trace shows `CONVERSATION_HISTORY_LOADED (n earlier exchanges)` when history is used |
| Trace shows `LLM_MODEL_FALLBACK: <model> -> <backup> (HTTP 503)` | The configured model was busy and the backup answered | Expected. Calls stay on the backup for 60 s, then try the configured model again. If every model is busy the circuit breaker takes over |
| Agentic turn fails with HTTP 400 `missing a thought_signature` | A Gemini 3 tool call was replayed without its signature. The signature interceptor is registered only when `OPENAI_BASE_URL` contains `generativelanguage.googleapis.com` | Use Gemini's real base URL (a proxy URL without that host turns it off). Keep the RestClient customizer order in `LlmHttpConfig` / `GeminiCompatConfig` (fallback last) |
| Turns take 20 to 100 s | `AGENT_LLM_ORCHESTRATION=agentic` on a Pro model (several full model calls per turn) | Use single-pass and Flash-Lite |
| `KafkaAdmin` timeouts at app startup on the host | Kafka not reachable at `localhost:9092` | Start the infra containers first |

Manual connector registration:
```powershell
$cfg = (Get-Content connector-config/register-postgres-cdc.json | ConvertFrom-Json).config | ConvertTo-Json
Invoke-RestMethod -Method Put http://localhost:8083/connectors/warehouse-postgres-connector/config -ContentType application/json -Body $cfg
```

---

## 5. Live LLM modes (Gemini Flash-Lite) and starting each mode

The offline planner is the safe default. Use live mode when you want the panel to see a real model write the replies. Decisions, citations and escalations are identical in both modes, because Java runs the tools before the model is called.

**Recommended settings** (put them in `.env`, which is git-ignored; never paste the key into a slide or a terminal that is being recorded):
```
OPENAI_API_KEY=<Gemini API key>
OPENAI_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai
OPENAI_COMPLETIONS_PATH=/chat/completions
OPENAI_MODEL=gemini-3.1-flash-lite
AGENT_LLM_MODE=llm
AGENT_LLM_ORCHESTRATION=single-pass
AGENT_LLM_REASONING_EFFORT=low
```
Then `docker compose up -d --force-recreate servicing-agent-app` and check the log for `Agent planner mode: LLM single-pass (Java tool plan + one model call)`.

**Avoiding 429s during the demo**
1. Use a key with billing. Free-tier keys allow about 20 requests per model per day (0 for Pro). Single-pass uses 1 request per turn; agentic uses 2 to 5.
2. Pace the scripts: `$env:CHAT_PACE_SECONDS = 8` before `demo.ps1 scenarios`, `seed`-driven runs or `demo-smoke.ps1`. Each chat call then waits until at least 8 s have passed since the previous one and prints `(pacing: waiting N s ...)`. In bash: `CHAT_PACE_SECONDS=8 scripts/demo.sh scenarios`.
3. If the provider still rejects a turn, `demo.ps1 chat` and `demo.sh chat` wait `CHAT_RETRY_WAIT_SECONDS` (default 30) and retry once. The app itself retries a failed model call once (0.5 s), switches to the backup model on a 503 if `AGENT_LLM_FALLBACK_MODELS` is set (section 5.2), and after 3 failed calls the circuit breaker skips the model for 60 s.
4. In Swagger, leave a few seconds between "Execute" clicks.
5. A 402 ("prepayment credits are depleted") or a quota 429 will not recover by waiting. Switch to offline: `AGENT_LLM_MODE=replay` and recreate the app (about 10 s).

**Before you present**
- `.\scripts\demo.ps1 caching cust_123 2`: one `LLM_ROUND_TRIP` per turn, `LLM_SYNTHESIS_ACCEPTED`, about 1.5 to 2.5 s per turn.
- Re-run Steps 2 and 3. The trace still shows `evaluateWaiverRules` and `escalateWithSummary`, and `COMPLIANCE_VIOLATION_RECORDED` does not appear.
- Then reset and seed again (section 1, step 2).

**Measured with this setup (containerized stack, Gemini, single-pass):** 1.4 to 2.7 s per turn, 1 model call, about 1,400 to 1,600 prompt tokens. The tools take under 40 ms in total; the rest is the model call. See the model comparison in `design.md` §6.

**Full live rehearsal:** `$env:GEMINI_API_KEY='<key>'` then `powershell -ExecutionPolicy Bypass -File scripts/demo-smoke-live.ps1`. It recreates the app in live mode with the settings above (key passed to Docker for that command only), runs the same checks as `demo-smoke.ps1` with a 16000 ms per-turn budget (15 s synthesis timeout, 2048 output tokens) and 8 s pacing, prints how many replies the model wrote versus fell back to the draft, then restores offline mode. Add `-KeepLive` to stay live for the demo. On 2026-11-21 it passed 30/30. Per-turn times varied with Google's load: one run had 1.5 to 2.0 s turns with 4 of 6 replies from the model; an earlier run the same day saw 3.3 to 8 s, a 503 "high demand" and 4 of 6 timeouts. Rehearse at the time of day you will present.

`AGENT_LLM_ORCHESTRATION=agentic` is agent planner mode (below). It makes 3 to 5 model calls per turn, so present it as a deliberate segment, not as the default live mode.

**Which mode to present.** Deterministic mode is the safe default (1.5 s per turn). LLM mode is the live demo: the model connects the dots and writes the answer, with Java checking every word (16 s budget). Agent planner mode shows the model choosing the tools itself (60 s budget). The trace shows the model's tool calls and `AGENTIC_VERIFIED`. See `design.md` §4.9.

### 5.1 Starting each mode

Run every command from the repo root. The app reads its mode at startup, so each switch recreates the `servicing-agent-app` container (about 10 to 15 s); Postgres, Kafka, Redis and the demo data are untouched. Pass the key in the shell (`$env:GEMINI_API_KEY`), never in a file you commit.

| Mode | Start it | Expect in `docker logs servicing-agent-app` | SLA |
|---|---|---|---|
| Deterministic | Open a **new** PowerShell window (no key set), then `docker compose up -d --force-recreate servicing-agent-app` | `Agent planner mode: DETERMINISTIC_REPLAY (offline)` | 1.5 s |
| LLM mode | `$env:GEMINI_API_KEY='<key>'` then `powershell -ExecutionPolicy Bypass -File scripts\demo-smoke-live.ps1 -KeepLive` | `Agent planner mode: LLM single-pass (Java tool plan + one model call)` | 16 s |
| Agent planner | `$env:GEMINI_API_KEY='<key>'` then `powershell -ExecutionPolicy Bypass -File scripts\demo-smoke-live.ps1 -Orchestration agentic -KeepLive` | `Agent planner mode: LLM agentic (model calls tools)` | 60 s |

`demo-smoke-live.ps1 -KeepLive` first runs the full live e2e (about 2 to 4 minutes, and it changes demo data), then leaves the app in that mode. To switch without the e2e run, set the variables in the shell and recreate the app yourself:
```powershell
$env:OPENAI_API_KEY = '<Gemini key>'
$env:OPENAI_BASE_URL = 'https://generativelanguage.googleapis.com/v1beta/openai'
$env:OPENAI_COMPLETIONS_PATH = '/chat/completions'
$env:OPENAI_MODEL = 'gemini-3.1-flash-lite'
$env:AGENT_LLM_MODE = 'llm'
$env:AGENT_LLM_ORCHESTRATION = 'single-pass'      # or 'agentic' for agent planner mode
$env:AGENT_LLM_REASONING_EFFORT = 'low'
$env:AGENT_LLM_SYNTHESIS_TIMEOUT_MS = '15000'
$env:AGENT_LLM_SYNTHESIS_MAX_TOKENS = '2048'
docker compose up -d --force-recreate servicing-agent-app
```
Check the mode at any time: `docker logs servicing-agent-app 2>&1 | Select-String "Agent planner mode" | Select-Object -Last 1`.

**Back to deterministic** from either LLM mode: close that window (the variables live only in that shell), open a new one and run `docker compose up -d --force-recreate servicing-agent-app`. If a key is in `.env`, add `AGENT_LLM_MODE=replay` there instead.

**Mid-demo fallback:** if the provider is slow or out of quota, switch to deterministic as above. Decisions, citations and escalations are the same in every mode.

### 5.2 `.env` for each mode, with backup models

Keep the mode in `.env` (git-ignored). Replace the whole file with one of the blocks below, put your key on the first line, then run:
```powershell
docker compose up -d --force-recreate servicing-agent-app
```
Variables set in the shell override `.env`, so run it from a fresh window. Check the mode with `docker logs servicing-agent-app 2>&1 | Select-String "Agent planner mode" | Select-Object -Last 1`.

**Deterministic** (offline, 1.5 s SLA). Log: `Agent planner mode: DETERMINISTIC_REPLAY (offline)`
```
OPENAI_API_KEY=<Gemini API key>
OPENAI_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai
OPENAI_COMPLETIONS_PATH=/chat/completions
AGENT_LLM_MODE=replay
```

**LLM mode, single-pass** (1 model call per turn, 16 s SLA). Log: `Agent planner mode: LLM single-pass (Java tool plan + one model call)`
```
OPENAI_API_KEY=<Gemini API key>
OPENAI_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai
OPENAI_COMPLETIONS_PATH=/chat/completions
AGENT_LLM_MODE=llm
AGENT_LLM_ORCHESTRATION=single-pass
AGENT_LLM_REASONING_EFFORT=low
OPENAI_MODEL=gemini-3.1-flash-lite
AGENT_LLM_FALLBACK_MODELS=gemini-3.5-flash
AGENT_LLM_SYNTHESIS_TIMEOUT_MS=30000
AGENT_LLM_SYNTHESIS_MAX_TOKENS=2048
AGENT_LLM_HTTP_READ_TIMEOUT_SECONDS=60
```

**Agent planner** (2 to 5 model calls per turn, 60 s SLA). Log: `Agent planner mode: LLM agentic (model calls tools)`
```
OPENAI_API_KEY=<Gemini API key>
OPENAI_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai
OPENAI_COMPLETIONS_PATH=/chat/completions
AGENT_LLM_MODE=llm
AGENT_LLM_ORCHESTRATION=agentic
AGENT_LLM_REASONING_EFFORT=low
OPENAI_MODEL=gemini-3.6-flash
AGENT_LLM_FALLBACK_MODELS=gemini-3.5-flash
AGENT_LLM_HTTP_READ_TIMEOUT_SECONDS=60
```

| Mode | When the model is busy (503) | When it is slow or fails |
|---|---|---|
| Deterministic | No model call | No model call |
| LLM mode (single-pass) | One call to the backup | Past the 15 s synthesis cap, or any other error: the deterministic draft |
| Agent planner | The call goes to the backup and stays there for 60 s | A 30 s timeout or other error: `AGENTIC_FALLBACK`, the deterministic answer |

In every LLM mode, 3 failed calls in a row (every model busy, or timeouts) open the circuit breaker: for 60 s turns skip the model and return the deterministic answer straight away (`LLM_CIRCUIT_OPEN` in the trace).

**What was tested (2026-11-24), agent planner mode:**
- `gemini-3.6-flash` primary with `gemini-3.5-flash` as backup: full turns with 3 model round trips, all HTTP 200.
- A forced 503 on the primary (local proxy): the trace showed `LLM_MODEL_FALLBACK: <primary> -> gemini-3.5-flash (HTTP 503)` and the turn completed on the backup, with no HTTP 400.
- Every model busy: the circuit opened after 3 failures and turns returned the deterministic answer in 2 to 5 s.
- Gemini was heavily loaded that night. Planner turns took 27 to 130 s, and some calls hit the 30 s timeout and fell back. `gemini-3.8-flash` and `gemini-3.1-flash-lite` returned 503 on every call, and `gemini-2.5-flash` returns 404 for new users.
- The single-pass row was not re-run that night. Single-pass replays no tool calls, so any model works there. A Flash backup is slower than Flash-Lite, and on a slow day it may exceed the synthesis cap and return the draft.

**Rules for picking a backup:** use a model other than `OPENAI_MODEL` (an identical entry is ignored), from the same family. List several, comma-separated, to try them in order. Before the demo, run one turn in the mode you will present and check the trace for `LLM_ROUND_TRIP ... (HTTP 200)`.

---
## 6. Enabling JWT security

Set `OAUTH2_ISSUER_URI` to your IdP's issuer URL in `.env` and restart the app. Tokens then need `customer.chat` for `/api/agent/**` and `servicing.supervisor` for `/api/admin/**`. `/api/demo/**`, Swagger and health stay open. Use Swagger's **Authorize** button with a bearer token.
