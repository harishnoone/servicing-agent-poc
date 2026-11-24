# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The project is a POC and is not versioned, so entries are grouped by date.
Per-requirement status lives in `docs/design.md` §8; spec-level history is in the changelog at the bottom of each file in `docs/`.

## [Unreleased]

### Added
- **Actions through Kafka; the app no longer writes source data (FR-9).** Approved waivers and escalations are published as action events (`agent.actions.waiver`, `agent.actions.escalation`; `ticket-note` and `sla-credit` reserved) with content-derived ids and an `Action produced: ... payload={...}` log line. A downstream `SystemOfRecordWriter` (consumer group `system-of-record`) applies each action once (`applied_actions`) and writes the audit row; CDC brings the result back. An approved waiver now really waives the invoice and counts against the 12-month limit.

- **Cost on the KPI endpoint.** `GET /api/agent/reports/kpis` adds `llmEstimatedCostUsd`, `llmCostIfPrefixCachedUsd` and `projectedCachingSavingPercent`, the running totals of the `Turn cost:` lines. Live agent planner turn: est. `$0.001364`, 35.87% projected saving. README and `design.md` §4.6a document every field.
- **Scenarios check the actions.** Scenario 3 and `demo-smoke.ps1` check the escalation action is published and applied (`ESCALATION_HANDOFF`); smoke resets Elena's waiver count first because approvals now change data.

### Changed
- **Admin override publishes instead of writing.** `POST /api/admin/overrides/waiver` validates with reads, publishes a waiver action and returns `status: ACCEPTED` with `actionId` (was `APPLIED` with `auditId`). Repeating the same override applies it once. Scenario 2 now resets Elena before each run, since approvals change data; scenario 8 checks the repeated override.

- **Per-turn cost log (FR-3.7).** Every turn with a model call logs `Turn cost: llmCalls=... promptTokens=... completionTokens=... staticPrefixTokens=... estCostUsd=... ifPrefixCachedUsd=... projectedSaving=...% providerReportedCachedTokens=...`. Tokens are summed over all model calls in the turn. Prices come from `agent.llm.pricing.*` (`AGENT_LLM_PRICE_INPUT`, `_OUTPUT`, `_CACHED_INPUT`; defaults are placeholders, set your contract prices). The saving is a ceiling: Gemini's OpenAI-compatible endpoint reports no cached tokens. Measured on scenario 11: LLM mode 1 call, 1,982 prompt tokens, est. $0.000265, 31% projected saving; agent planner 5 calls, 12,212 prompt tokens, est. $0.001364, 30%.
- **Ticket status inquiry (FR-6.5).** "What's the status of my ticket?", "any update on my ticket" or a ticket id now lists the customer's open tickets (or the named one) with subject, status and SLA time left or hours breached; a breached ticket escalates. Scenario `scripts/scenarios/12-ticket-status.ps1` (Elena within SLA, Marcus past SLA, vague question). Passed offline and in live agent planner mode. Tests: 93.

### Fixed
- **Scenario 10 failed in agent planner mode.** It required exactly one model call per turn, which only holds in LLM mode; agent planner mode makes several (3 per turn on Gemini). The check now depends on the mode; the caching checks (same prefix on every call, usage reported) are unchanged. Passed in agent planner mode.
- **Agent planner mode could skip escalation.** Live, the model answered Marcus's "What is my status?" without calling `escalateWithSummary`, so a breached ticket was not escalated. Java now runs the escalation check after every agentic turn in which the model did not (`AGENTIC_ESCALATION_ENFORCED` in the trace).

### Fixed
- **Agent planner turns could hang on a stalled model call.** The RestClient used for the model had no read timeout, so one slow Gemini response blocked the turn indefinitely (seen when running scenario 11 twice in a row; not related to prompt caching). Model calls now have a 5 s connect and 15 s read timeout (`agent.llm.http-connect-timeout-seconds`, `agent.llm.http-read-timeout-seconds`), and a failed agentic turn returns the deterministic answer (`AGENTIC_FALLBACK`) instead of the generic failure message. Verified back to back on Gemini: one stalled call fell back in 16.5 s; the other two runs were model-planned and verified (15 s, 30 s).

### Added
- **Agent planner mode for investigations (agentic).** The model can now plan the billing investigation itself: `checkEligibility` is a `@Tool` (eligibility without approval), system prompt rule 7 describes the investigation, and Java verifies the model's final answer (`AGENTIC_VERIFIED`, else the deterministic answer is returned). Rule-engine policy citations count as grounded sources. Scenario 11 checks model-planned runs too; 3 of 3 passed on Gemini Flash-Lite (5 model calls, 4.1 to 47 s).
- **Three operating modes with their own latency SLA (NFR-5):** deterministic 1.5 s, LLM mode 16 s, agent planner 60 s. Documented in README, `design.md` §4.9 and the playbook. `demo-smoke-live.ps1 -Orchestration agentic` runs the live e2e in agent planner mode.
- **Multi-step billing investigation (FR-3.10).** "Why is my bill so high?" now runs five traced steps: ledger analysis, open-ticket check, late-fee eligibility without applying it (`checkWaiverEligibility`, no audit), SLA-credit policy for an open outage, and verification of every amount, invoice, ticket and citation before answering (escalates on failure). Single-pass LLM rewrites that add an unverified dollar amount are rejected (`UNVERIFIED_AMOUNT`). New persona Riley Morgan (`cust_246`) in `seed-data.txt` and scenario `scripts/scenarios/11-billing-investigation.ps1` (self-seeding). Tests: 90 (88 pass, 2 live-LLM skip).
- **Live investigation replies.** The single-pass prompt now tells the model the late-fee waiver was checked but NOT applied (say "eligible", never "approved" or "you qualify") and to keep every investigation finding. Before, the approval guard rejected the rewrite (`UNBACKED_APPROVAL`) or thinking tokens used up the 1,024-token budget, so the customer always saw the deterministic draft. Live runs now use a 15 s synthesis timeout and 2,048 output tokens (`demo-smoke-live.ps1`; per-turn budget 16000 ms in the live scripts). Verified on Gemini Flash-Lite: 4 of 4 investigation replies written by the model, 4.1 to 4.7 s.
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
