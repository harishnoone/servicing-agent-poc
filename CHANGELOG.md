# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The project is a POC and is not versioned, so entries are grouped by date.
Per-requirement status lives in `docs/design.md` §8; spec-level history is in the changelog at the bottom of each file in `docs/`.

## [Unreleased]

### Added
- **`scripts/decision-probe.ps1`:** standalone probe that runs the demo questions, adversarial prompts and RAG chunk grading against `gemini-decision-1`. Reads `GEMINI_API_KEY` from the environment. Results are in `docs/design.md` §6 (11/11 intents, 0 output tokens, mean 295 ms). Not wired into the application.

### Changed
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
