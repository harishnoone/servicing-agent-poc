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
   To follow turns live during the demo: `docker logs -f servicing-agent-app 2>&1 | Select-String "Turn |LLM round trip"`. Every line carries `[sessionId customerId]`.
5. Live mode only: run `.\scripts\demo.ps1 caching cust_123 2` once. Each turn must show exactly one `LLM_ROUND_TRIP #1 (HTTP 200)` and `LLM_SYNTHESIS_ACCEPTED`. If you see 429 or 402, fix the key before the demo (section 5).
6. Rehearse once: `.\scripts\demo.ps1 scenarios` prints every seeded question with its outcome, tool trace and citations. For a pass/fail check, run `powershell -ExecutionPolicy Bypass -File scripts/demo-smoke.ps1` (`ALL CHECKS PASSED`). **Then reset and seed again** (step 2): both scripts change demo data.
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

### Step 4: KPIs

**Agent → GET /api/agent/reports/kpis**: sessions, deflection rate, AHT minutes saved, compliance score, cache-miss fallbacks and DLT depth. In live mode also `llmCalls`, `promptTokens`, `cachedPromptTokens` and `promptCacheHitRatePercent` (cached tokens stay 0 on Gemini; see section 5).

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
| Turns take 20 to 100 s | `AGENT_LLM_ORCHESTRATION=agentic` on a Pro model (several full model calls per turn) | Use single-pass and Flash-Lite |
| `KafkaAdmin` timeouts at app startup on the host | Kafka not reachable at `localhost:9092` | Start the infra containers first |

Manual connector registration:
```powershell
$cfg = (Get-Content connector-config/register-postgres-cdc.json | ConvertFrom-Json).config | ConvertTo-Json
Invoke-RestMethod -Method Put http://localhost:8083/connectors/warehouse-postgres-connector/config -ContentType application/json -Body $cfg
```

---

## 5. Live LLM mode (Gemini Flash-Lite)

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
3. If the provider still rejects a turn, `demo.ps1 chat` and `demo.sh chat` wait `CHAT_RETRY_WAIT_SECONDS` (default 30) and retry once. The app itself retries 429/503 at most 3 times (1 s, then 2 s).
4. In Swagger, leave a few seconds between "Execute" clicks.
5. A 402 ("prepayment credits are depleted") or a quota 429 will not recover by waiting. Switch to offline: `AGENT_LLM_MODE=replay` and recreate the app (about 10 s).

**Before you present**
- `.\scripts\demo.ps1 caching cust_123 2`: one `LLM_ROUND_TRIP` per turn, `LLM_SYNTHESIS_ACCEPTED`, about 1.5 to 2.5 s per turn.
- Re-run Steps 2 and 3. The trace still shows `evaluateWaiverRules` and `escalateWithSummary`, and `COMPLIANCE_VIOLATION_RECORDED` does not appear.
- Then reset and seed again (section 1, step 2).

**Measured with this setup (containerized stack, Gemini, single-pass):** 1.4 to 2.7 s per turn, 1 model call, about 1,400 to 1,600 prompt tokens. The tools take under 40 ms in total; the rest is the model call. See the model comparison in `design.md` §6.

`AGENT_LLM_ORCHESTRATION=agentic` lets the model plan and call the tools itself. It is kept for comparison but is slower (2 to 5 model calls per turn), so do not use it in the live demo.

---
## 6. Enabling JWT security

Set `OAUTH2_ISSUER_URI` to your IdP's issuer URL in `.env` and restart the app. Tokens then need `customer.chat` for `/api/agent/**` and `servicing.supervisor` for `/api/admin/**`. `/api/demo/**`, Swagger and health stay open. Use Swagger's **Authorize** button with a bearer token.
