# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The project is a POC and is not versioned, so entries are grouped by date.
Per-requirement status lives in `docs/design.md` §8; spec-level history is in the changelog at the bottom of each file in `docs/`.

## [Unreleased]

### Added
- **Multi-step billing investigation (FR-3.10).** "Why is my bill so high?" now runs five traced steps: ledger analysis, open-ticket check, late-fee eligibility without applying it (`checkWaiverEligibility`, no audit), SLA-credit policy for an open outage, and verification of every amount, invoice, ticket and citation before answering (escalates on failure). Single-pass LLM rewrites that add an unverified dollar amount are rejected (`UNVERIFIED_AMOUNT`). New persona Riley Morgan (`cust_246`) in `seed-data.txt` and scenario `scripts/scenarios/11-billing-investigation.ps1` (self-seeding). Tests: 90 (88 pass, 2 live-LLM skip).
- `scripts/scenarios/10-prompt-caching.ps1`: live-LLM prompt-caching scenario. Checks one model call per turn, usage reported, and the same static-prefix hash across turns and across two customers (no customer data in the cached prefix). Reports SKIPPED in deterministic mode. A turn whose model call hits the 8 s limit is counted as unanswered, not as a failure. Passed live on 2026-11-21: 3 of 3 turns answered, one call each, prefix 3da196aab318 on both customers.

### Fixed
- **Incomplete profiles after out-of-order or post-eviction CDC events (FR-1.5).** A ticket or billing event that arrived before its customer event, or after the profile was evicted, used to create a stub (blank name, Standard tier, 0 tenure) that the agent read as a normal cache hit, which could wrongly deny a waiver. The consumer now builds the full profile from the warehouse once (bounded lookups) when the profile is missing or a stub, then stays incremental; a warehouse failure retries the event. The agent treats any stub as a cache miss. Tests: 87 (85 pass, 2 live-LLM skip). Not yet verified on the live stack.

### Added
- **No duplicate tickets for the same problem (FR-6.3, FR-6.4).** New deterministic `TicketMatchTools`. The agent runs `findMatchingOpenTicket` for problem reports and complaints: when an open ticket already covers the request (ticket id, shared error code or same topic), the reply treats the message as a follow-up on that ticket and the trace shows `EXISTING_TICKET <id> (<reason>)`. Waiver requests and policy questions are excluded. `POST /api/demo/tickets` no longer inserts a duplicate: it returns the existing ticket with `duplicate=true` and `matchReason`, reading the warehouse under a per-customer row lock. Registered as a tool for agentic mode and passed to single-pass synthesis.
- **CDC consume logging (FR-7.5).** `IdempotentKafkaConsumer` logs one `CDC applied table=... id=... created|updated` INFO line per applied customer, billing or ticket event, with `customerId` in the MDC and ids only.
- **Per-scenario demo scripts.** `scripts/scenarios/01` to `09` run one scenario each with PASS/FAIL checks; `scripts/scenarios/run.ps1` picks them from a menu or by number. `scripts/demo-smoke.ps1` stays the full end-to-end run and now has 29 checks, including the duplicate-ticket scenario and a check that every turn ran on the expected planner.
- **Live-LLM end-to-end run: `scripts/demo-smoke-live.ps1`.** Recreates the app in live mode (Gemini Flash-Lite, single-pass), runs `demo-smoke.ps1 -Mode llm` with a 9000 ms per-turn budget (8 s synthesis timeout plus tools) and 8 s pacing, reports how many replies the model wrote, then restores offline mode (`-KeepLive` to stay live). The key comes from `GEMINI_API_KEY` or `OPENAI_API_KEY` in the shell and is never written to a file. `demo-smoke.ps1` takes `-Mode deterministic|llm` and `-MaxLatencyMs`; the deterministic default stays 1500 ms. Verified 2026-11-21: deterministic 29/29; live 30/30, with 4 of 6 replies written by the model and 2 falling back to the draft.
- Tests: 82 (80 pass, 2 live-LLM tests skip without a key), including `TicketMatchToolsTest`, three new `DemoScenariosTest` cases and `IdempotentKafkaConsumerTest.logsEveryAppliedEventWithIdsOnly`.
- **`scripts/decision-probe.ps1`:** standalone probe that runs the demo questions, adversarial prompts and RAG chunk grading against `gemini-decision-1`. Reads `GEMINI_API_KEY` from the environment. Results are in `docs/design.md` §6 (11/11 intents, 0 output tokens, mean 295 ms). Not wired into the application.

### Changed
- `demo.ps1 ticket` and `demo.sh ticket` report "Not created: open ticket ... already covers this problem" instead of waiting for a ticket that was not created.
- Agent system prompt rule 6: call `findMatchingOpenTicket` for problem reports.
- **Decision recorded: no decision model in the POC.** The POC stays on Gemini Flash-Lite plus deterministic Java guardrails. `gemini-decision-1` is documented as the production scale-up path (pre-turn safety screen, intent and escalation triage, RAG chunk grading) in `docs/design.md` §6 and §9, `docs/requirements.md` §5 and §6, and `docs/infrastructure.md`. Documentation only; no code change.

## 2026-11-20

### Added
- **Single-pass LLM orchestration (FR-3.8), now the default for LLM mode.** Java runs the tool plan, then one model call with no tools writes the reply (`SingleCallSynthesizer`, `synthesisChatClient`). 8 s hard timeout; on timeout, error, truncated reply, unknown or dropped citations, or an unbacked approval claim, the deterministic draft is returned. `AGENT_LLM_ORCHESTRATION=agentic` keeps model-driven tool calling.
- **Gemini support via the OpenAI-compatible endpoint.** `GeminiThoughtSignatureInterceptor` round-trips Gemini 3 tool-call `thought_signature`s, which Spring AI 1.0 drops (needed for agentic mode).
- **Per-turn and per-LLM-call logging (FR-7.4).** `logback-spring.xml` with `[sessionId customerId]` MDC on every line; `Turn started` / `Turn finished` lines; `LlmCallLoggingInterceptor` logs each model call (status, duration, usage) and adds `LLM_ROUND_TRIP #n` to the reasoning trace.
- **Rate-limit handling (FR-3.9).** Bounded Spring AI retry (3 attempts on 429/503). `CHAT_PACE_SECONDS` and `CHAT_RETRY_WAIT_SECONDS` in `demo.ps1`, `demo.sh` and `demo-smoke.ps1`.
- `demo.ps1 caching` / `demo.sh caching`: prompt-caching demo (same question N times; round trips, latency and tokens per turn).
- Synthesis settings: `AGENT_LLM_SYNTHESIS_TIMEOUT_MS`, `AGENT_LLM_SYNTHESIS_MAX_TOKENS`, `AGENT_LLM_REASONING_EFFORT`, all passed through `compose.yaml`.
- Tests: `SinglePassModeTest`, `GeminiThoughtSignatureInterceptorTest`, and live tests `LiveCachingOnTest` / `LiveCachingOffTest` (run only when `OPENAI_API_KEY` is set). 69 tests in total.
- Smoke check that the KPI report exposes the prompt-cache fields (23 checks).
- `docs/` directory for all specs and runbooks; this changelog.

### Changed
- Recommended live model: `gemini-3.1-flash-lite` with `reasoning_effort=low` (1.4 to 2.7 s per turn, measured in containers). The comparison against Gemini Pro and agentic mode is in `docs/design.md` §6.
- The synthesis prompt forbids claiming a bill or ledger change and inventing ticket details (seen in a live reply).
- Moved `requirements.md`, `design.md`, `infrastructure.md` and `playbook.md` into `docs/`.
- `.env.example` and the README document Gemini settings; retired model ids (`gemini-1.5-pro`, `gemini-2.x`) are no longer suggested.

### Added
- `scripts/scenarios/10-prompt-caching.ps1`: live-LLM prompt-caching scenario. Checks one model call per turn, usage reported, and the same static-prefix hash across turns and across two customers (no customer data in the cached prefix). Reports SKIPPED in deterministic mode. A turn whose model call hits the 8 s limit is counted as unanswered, not as a failure. Passed live on 2026-11-21: 3 of 3 turns answered, one call each, prefix 3da196aab318 on both customers.

### Fixed
- `docs/requirements.md`: restored Requirements 4 to 8, which an earlier edit had removed.

## 2026-11-19

### Added
- **Prompt caching (FR-3.5 to FR-3.7).** `StaticPromptPrefix` (byte-stable system message from the system prompt, `prompts/policy-digest.md` and `prompts/few-shot-examples.md`; content hash logged), `CacheUsageRecorder`, and `llmCalls`, `promptTokens`, `cachedPromptTokens`, `promptCacheHitRatePercent` in `GET /api/agent/reports/kpis`. `AGENT_PROMPT_CACHING=false` for A/B comparison.
- Demo seeding: `scripts/demo.ps1`, `scripts/demo.sh`, `scripts/seed-data.txt` (8 extra customers), demo endpoints for customers, tickets, profile and poison pill.
- Requirements traceability and verification (`docs/design.md` §8).

## 2026-11-18

### Added
- Initial POC: Debezium CDC from Postgres into Kafka (KRaft), idempotent consumer with per-row LSN guards into Redis, non-blocking retry topics and DLT with inspect and replay, asymmetric cache-miss fallback, hybrid BM25 plus vector retrieval with authority weighting, deterministic waiver rules, escalation with handoff card, `AgentTurnReport`, KPI report, OAuth2 supervisor overrides with an append-only audit log, offline replay planner, Swagger UI, 5-container `compose.yaml`.
