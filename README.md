# Servicing Agent POC

A Tier-1 customer servicing agent you can run on a laptop. It answers billing, ticket and troubleshooting questions from live customer state and canonical policy documents. A language model writes the replies; **every financial decision, citation and escalation comes from deterministic Java code**.

Java 21 · Spring Boot 3.5 · Spring AI 1.0 · Postgres 16 · Debezium · Kafka (KRaft) · Redis 7 · Docker Compose

- **Live customer state without warehouse latency.** Debezium streams warehouse changes through Kafka into Redis. The agent reads Redis in under a millisecond and falls back to one bounded warehouse lookup only on a cache miss.
- **Exactly-once effect on an at-least-once pipeline.** Deduplication by event id plus a per-row LSN guard; non-blocking retries (1 s, 2 s, 4 s) and a dead-letter topic with inspect and replay.
- **Grounded answers.** Hybrid BM25 plus vector retrieval in which canonical policy outranks historical case notes. Every policy claim carries a `[doc_id]` citation that is checked against what retrieval actually returned.
- **Deterministic guardrails.** Waiver eligibility, SLA-breach escalation and the Tier-2 handoff card are computed in Java. The model cannot approve anything.
- **Fast with a real model.** In LLM mode, Java runs the tools and the model is called **once** per turn (about 1.5 to 2.7 s on Gemini Flash-Lite), with an 8 s cap and a deterministic fallback.
- **Runs offline.** No API key needed: an offline planner drives the same tools and guardrails.
- **Observable.** Each response includes the full reasoning trace with per-step latency; logs carry session and customer ids; a KPI endpoint reports deflection, compliance, cache misses, DLT depth and LLM token usage.

## Contents

- [Quick start](#quick-start)
- [How it works](#how-it-works)
- [Try it](#try-it)
- [Using a real LLM](#using-a-real-llm)
- [API](#api)
- [Configuration](#configuration)
- [Development](#development)
- [Documentation](#documentation)
- [Known limitations](#known-limitations)

## Quick start

Prerequisites: Docker Desktop with Compose v2 and about 4 GB of free RAM. JDK 21 is needed only to run the tests on the host (Maven comes with `mvnw`).

```bash
git clone https://github.com/harishnoone/servicing-agent-poc.git
cd servicing-agent-poc
cp .env.example .env          # optional; works unchanged (offline planner)
docker compose up --build -d
```

After about a minute `http://localhost:8080/actuator/health` returns `UP`. Open **http://localhost:8080/swagger-ui.html** and run **POST /api/agent/chat**:

```json
{ "customerId": "cust_123", "message": "Why is my bill $45 higher, and can you waive it?" }
```

The app registers the Debezium connector on startup, and the initial snapshot loads three demo customers into Redis within seconds. Stop with `docker compose stop` (keeps data) or `docker compose down -v` (wipes data; the next start re-seeds).

## How it works

```
Postgres (warehouse) --WAL--> Debezium --> Kafka --> IdempotentKafkaConsumer --> Redis (customer profiles)
                                             |
                                             +--> <topic>-retry-1000 / -2000 / -4000 --> <topic>.DLT

POST /api/agent/chat
  PII redaction --> tool plan --> (one model call writes the reply) --> citation check --> AgentTurnReport
                       |
     getCustomerState        Redis; on a miss, bounded warehouse lookup + async re-hydration
     searchPoliciesAndCases  BM25 + dense hybrid search; canonical policy outranks case notes
     evaluateWaiverRules     pure Java: tier, tenure, amount, prior waivers
     escalateWithSummary     SLA breach / frustration / low retrieval confidence -> handoff card
     getDeflectionReport     KPI aggregate
```

A turn's response (trimmed):

```json
{
  "agentReasoningTrace": [
    { "event": "PII_SANITIZATION_PASSED", "latencyMs": 0 },
    { "event": "PLANNER: LLM_SINGLE_PASS", "latencyMs": 0 },
    { "event": "TOOL_CALL: getCustomerState (REDIS_HIT)", "latencyMs": 1 },
    { "event": "TOOL_CALL: searchPoliciesAndCases (confidence=0.98)", "latencyMs": 5 },
    { "event": "TOOL_CALL: evaluateWaiverRules -> APPROVED", "latencyMs": 27 },
    { "event": "TOOL_CALL: escalateWithSummary -> NO_ESCALATION_NEEDED", "latencyMs": 1 },
    { "event": "LLM_ROUND_TRIP #1 (HTTP 200)", "latencyMs": 1779 },
    { "event": "LLM_SYNTHESIS_ACCEPTED", "latencyMs": 1 }
  ],
  "finalResponse": "... you meet the eligibility criteria for a waiver [billing_waivers_policy.md#sec-4.2] ...",
  "citations": ["billing_waivers_policy.md#sec-4.2"],
  "kpiImpact": { "deflectionStatus": "RESOLVED_AUTONOMOUSLY", "totalTurnLatencyMs": 2052 },
  "handoffCard": null
}
```

`deflectionStatus`, the handoff card and the citations come from tool results recorded during the turn, never from parsing the model's text.

## Try it

`scripts/demo.ps1` (Windows PowerShell) and `scripts/demo.sh` (bash + curl; `jq` optional) take the same commands. Every write goes to Postgres and reaches Redis through real CDC; each command waits until the change has arrived.

```powershell
.\scripts\demo.ps1 seed                    # 8 more customers, one per outcome (about 1 s)
.\scripts\demo.ps1 scenarios               # ask every question in seed-data.txt, print a summary per turn
.\scripts\demo.ps1 charge cust_123 LATE_FEE 45
.\scripts\demo.ps1 customer cust_900 "Dana Lee" Gold 40 499
.\scripts\demo.ps1 ticket cust_900 PENDING_ENGINEERING -3 Gateway down   # negative hours = SLA already breached
.\scripts\demo.ps1 chat cust_900 "Can you waive my late fee?"
.\scripts\demo.ps1 profile cust_900        # what the agent sees in Redis
.\scripts\demo.ps1 evict cust_900          # force a cache miss
.\scripts\demo.ps1 poison                  # bad CDC message -> retries -> DLT
.\scripts\demo.ps1 caching cust_123 3      # LLM mode: one model call per turn, tokens and latency
.\scripts\demo.ps1 kpis | dlt | replay | reset
```

| Customer | Expected result |
|---|---|
| `cust_123` Elena Vance | Waiver `APPROVED` |
| `cust_456` Marcus Sterling | ERR-504-GW fix, superseded legacy advice flagged, `SLA_BREACH` escalation with handoff card |
| `cust_789` Jordan Blake | `TIER_INELIGIBLE`, clean denial |
| `cust_222` Noah Patel | Waiver `APPROVED` |
| `cust_321` Priya Nair | `TENURE_TOO_SHORT`, escalated for supervisor review |
| `cust_654` Diego Alvarez | `FEE_TOO_HIGH` ($75) |
| `cust_444` Omar Haddad | `TIER_INELIGIBLE` |
| `cust_555` Mia Rossi | `WAIVER_LIMIT_REACHED` |
| `cust_987` Sam Okafor | `HIGH_FRUSTRATION` escalation |
| `cust_333` Lena Fischer | ERR-504-GW fix + `SLA_BREACH` escalation |
| `cust_111` Ava Chen | SLA-credit policy answer |

The first three exist after startup; the rest after `seed`. All customer data is fictional. Add your own by appending `POST` and `CHAT` lines to `scripts/seed-data.txt`.

Verify everything end to end (23 checks, prints `ALL CHECKS PASSED`; run on a fresh stack because it changes data):

```powershell
powershell -ExecutionPolicy Bypass -File scripts/demo-smoke.ps1
```

A step-by-step demo script and troubleshooting table are in [`docs/playbook.md`](docs/playbook.md).

## Using a real LLM

The LLM path uses Spring AI's OpenAI client, so any OpenAI-compatible endpoint works. Put the settings in `.env` (git-ignored) and run `docker compose up -d --force-recreate servicing-agent-app`.

**Google Gemini (tested, recommended):**

```
OPENAI_API_KEY=<your Gemini API key>
OPENAI_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai
OPENAI_COMPLETIONS_PATH=/chat/completions
OPENAI_MODEL=gemini-3.1-flash-lite
AGENT_LLM_MODE=llm
AGENT_LLM_REASONING_EFFORT=low
```

**OpenAI:** `OPENAI_API_KEY=sk-...` (model defaults to `gpt-4o`). Leave `AGENT_LLM_REASONING_EFFORT` empty, because `gpt-4o` rejects it. Not yet run end to end.

**Planner modes** (`AGENT_LLM_MODE`): `auto` (default: LLM if a real key is set, else offline), `llm`, or `replay` (offline). The startup log shows the active mode.

**Orchestration** (`AGENT_LLM_ORCHESTRATION`):

| Value | What happens | Model calls per turn |
|---|---|---|
| `single-pass` (default) | Java runs the tool plan, then one model call with no tools writes the reply. The deterministic draft is returned instead if the call takes longer than 8 s, fails, is cut off, cites an id retrieval did not return, drops the citations, or claims an approval the rule engine did not give | 1 |
| `agentic` | The model plans and calls the `@Tool` methods itself | 2 to 5, each a full round trip |

**Model choice.** Measured in the containerized stack: Flash-Lite in single-pass took 1.4 to 2.7 s per turn. `gemini-3.1-pro-preview` took 4 to 8 s in single-pass (3 of 4 turns hit the timeout) and 19 to 105 s in agentic mode. The model only words decisions Java has already made, so a larger model adds latency without adding correctness. The full comparison, with every parameter tested, is in [`docs/design.md`](docs/design.md) §6 ("Why Gemini 3.1 Flash-Lite").

**Rate limits and quotas.** Spring AI retries HTTP 429/503 at most 3 times (1 s, then 2 s). Free-tier Gemini keys allow about 20 requests per model per day and none for Pro models; a depleted prepaid balance returns HTTP 402. The scripts can pace calls: set `CHAT_PACE_SECONDS=8`, and `chat` retries a rejected turn once after `CHAT_RETRY_WAIT_SECONDS` (default 30).

**Gemini notes.** Gemini 3 returns a `thought_signature` on tool calls and rejects the follow-up request without it; Spring AI 1.0 drops the field, so `GeminiThoughtSignatureInterceptor` puts it back (agentic mode only). `gemini-1.5-pro` and the `gemini-2.x` ids return 404 for new keys.

**Prompt caching.** `StaticPromptPrefix` sends a byte-stable system message (rules plus `prompts/policy-digest.md`) and keeps customer data in the user message, so providers can cache the prefix. The KPI report shows `promptTokens`, `cachedPromptTokens` and `promptCacheHitRatePercent`; `AGENT_PROMPT_CACHING=false` turns it off for comparison. Gemini's OpenAI-compatible endpoint does not report cached tokens, so the hit rate reads 0% there.

**Logs.** `docker logs -f servicing-agent-app` shows `Turn started` / `Turn finished` (status, latency, tools, citations, model round trips) and one `LLM round trip #n HTTP 200 in ... ms, usage={...}` line per model call, each tagged `[sessionId customerId]`.

## API

Interactive docs: `http://localhost:8080/swagger-ui.html`.

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/agent/chat` | Run one agent turn; returns the `AgentTurnReport` |
| GET | `/api/agent/reports/kpis` | Sessions, deflection rate, AHT saved, compliance, cache misses, DLT depth, LLM usage |
| POST | `/api/demo/mutate` | Insert a charge into the warehouse (triggers CDC) |
| POST | `/api/demo/customers` | Create a customer (409 if it exists) |
| POST | `/api/demo/customers/{id}` | Update tier, tenure or waiver count |
| POST | `/api/demo/tickets` | Create a ticket; `slaHoursFromNow` < 0 means already breached |
| GET | `/api/demo/profile/{id}` | The cached Redis profile the agent sees |
| POST | `/api/demo/poison-pill` | Publish an unparseable CDC message to exercise retries and the DLT |
| DELETE | `/api/demo/cache/{id}` | Evict a Redis profile to simulate a dormant account |
| POST | `/api/admin/overrides/waiver` | Supervisor waiver override with an append-only audit entry |
| GET | `/api/admin/kafka/dlt` | Dead-letter depth and pending records with failure headers |
| POST | `/api/admin/kafka/dlt/replay` | Republish dead-letter records to the original topic |

Malformed requests return HTTP 400 with `violations: [{field, message}]`.

**Security.** Without `OAUTH2_ISSUER_URI` every endpoint is open (local demo) and overrides are audited as `demo-mode-no-idp`. With it set, JWT bearer tokens are required: `SCOPE_customer.chat` for `/api/agent/**` and `SCOPE_servicing.supervisor` for `/api/admin/**`, and the supervisor's `sub` is written to `audit_log`. `/api/demo/**` stays open for demos; remove or protect it anywhere else.

## Configuration

Thresholds, prompts and retrieval parameters live in [`src/main/resources/application.yml`](src/main/resources/application.yml) under `agent.*`; most can be overridden with environment variables (see [`.env.example`](.env.example)).

| Key | Default | Meaning |
|---|---|---|
| `agent.rules.waiver.max-fee-amount` | 50.00 | Largest fee Tier-1 may waive |
| `agent.rules.waiver.min-tenure-months` | 12 | Minimum tenure |
| `agent.rules.waiver.eligible-tiers` | Gold,Enterprise | Eligible tiers |
| `agent.rules.waiver.max-waivers-per-year` | 0 | Prior waivers allowed in 12 months |
| `agent.escalation.min-retrieval-confidence` | 0.72 | Below this, retrieval triggers escalation |
| `agent.retrieval.top-k` / `canonical-boost` | 3 / 2.0 | Results returned; canonical policy weight |
| `agent.cache-miss.fallback-enabled` / `query-timeout-seconds` | true / 1 | Warehouse fallback on cache miss |
| `agent.llm.mode` (`AGENT_LLM_MODE`) | auto | `auto`, `llm` or `replay` |
| `agent.llm.orchestration` (`AGENT_LLM_ORCHESTRATION`) | single-pass | `single-pass` or `agentic` |
| `agent.llm.synthesis.timeout-ms` / `max-tokens` / `reasoning-effort` | 8000 / 1024 / empty | Single-pass call limits |
| `agent.prompt-caching.enabled` (`AGENT_PROMPT_CACHING`) | true | Static prompt prefix for provider caching |
| `spring.ai.retry.max-attempts` / `on-http-codes` | 3 / 429,503 | Bounded provider retry |
| `agent.prompts.system` / `agent.prompts.synthesis` | see file | Agentic and single-pass system prompts |

Policies are Markdown files in `src/main/resources/policies/`; each `##`/`###` section becomes one citable chunk (e.g. `billing_waivers_policy.md#sec-4.2`). Historical cases are in `src/main/resources/cases/historical_cases.json`; set `superseded_by` to mark one as replaced by a policy.

## Development

```bash
./mvnw test              # 69 tests; the 2 live-LLM tests run only when OPENAI_API_KEY is set
./mvnw spring-boot:run   # app on the host; start the infra first: docker compose up -d warehouse-db kafka debezium redis
```

On Windows use `.\mvnw.cmd`. From the host Kafka is `localhost:9092` (containers use `kafka:29092`). When the app runs on the host, set `DEBEZIUM_CONNECT_URL=http://localhost:8083` or register the connector manually (see `docs/playbook.md`).

```
compose.yaml, Dockerfile           5-container stack (multi-stage build, non-root runtime)
connector-config/                  Debezium connector definition
scripts/                           demo.ps1, demo.sh, seed-data.txt, demo-smoke.ps1
src/main/java/io/github/harishnoone/servicingagent/
  cdc/ cache/                      Kafka consumer, connector registrar, warehouse fallback
  tools/                           Spring AI @Tool classes
  replay/ llm/ prompt/             Tool plan, single-pass model call, static prompt prefix
  controller/ admin/ dlt/          REST endpoints, supervisor overrides, audit log, DLT replay
  telemetry/ guardrails/           Trace, per-turn state, KPIs, PII redaction
  config/                          Kafka retry, Spring AI, LLM logging, Gemini support, security, OpenAPI
src/main/resources/                application.yml, logback-spring.xml, init.sql, policies/, cases/, prompts/
docs/                              Requirements, design, runbook
```

## Roadmap (not implemented)

The POC uses one generative model (Gemini Flash-Lite) plus deterministic Java guardrails. The planned production step is to put a dedicated decision model in front of it for three jobs: pre-turn safety screening, intent and escalation triage, and RAG chunk grading. The decision model would route and grade only; eligibility and approvals stay in Java. It would run in shadow mode first and fall back to today's behaviour on any failure. Measured on the 11 demo questions plus 3 adversarial ones with a standalone script (`scripts/decision-probe.ps1`): 11 of 11 intents correct, 0 output tokens, about 295 ms per call (results in [`docs/design.md`](docs/design.md) §6). Price and batch limits are not verified by us.

Details: [`docs/requirements.md`](docs/requirements.md) §6, [`docs/design.md`](docs/design.md) §9, [`docs/infrastructure.md`](docs/infrastructure.md) "Future infrastructure".

## Documentation

| File | What it covers |
|---|---|
| [`docs/requirements.md`](docs/requirements.md) | Functional requirements (EARS), KPI targets, scope |
| [`docs/design.md`](docs/design.md) | Architecture, data model, design decisions and the LLM model comparison; §8 maps every requirement to its code and test |
| [`docs/infrastructure.md`](docs/infrastructure.md) | Container topology and configuration |
| [`docs/playbook.md`](docs/playbook.md) | Demo runbook, live-LLM setup, rate-limit handling, troubleshooting |
| [`CHANGELOG.md`](CHANGELOG.md) | Release notes |

Requirement ids in code comments (e.g. `FR-3.8`) refer to `docs/requirements.md`.

## Known limitations

- The LLM path has been run against Gemini only, not OpenAI.
- With a model in the loop a turn takes about 1.4 to 2.7 s (the model call); the <1.5 s P95 target is met only by the offline planner (2 to 65 ms). Streaming the reply is the next step.
- Prompt-caching savings are unmeasured: Gemini's OpenAI-compatible endpoint does not report cached tokens.
- Single-pass mode uses a keyword-based tool plan; open-ended questions outside its intents get a general account summary (agentic mode handles them, more slowly).
- The vector half of hybrid search is a local character-trigram embedding so the stack runs offline; swap `KnowledgeRetrievalTools.embed()` for a real embedding model.
- KPI counters are in memory; "deflection" means resolved without escalation, not a measured re-contact rate.
- Autonomous approvals are written to `audit_log` only; nothing is written back to a billing system.
- No server-side throttle around the model call (client pacing and bounded retries only).
- No prompt-injection screen or model-based intent triage. This is deliberate for the POC (one generative model plus deterministic Java guardrails). The production path puts a dedicated decision model (`gemini-decision-1`) in front of Flash; see [`docs/design.md`](docs/design.md) §9.

Full per-requirement status: [`docs/design.md`](docs/design.md) §8.
