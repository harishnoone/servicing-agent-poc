# Requirements Specification: Enterprise Servicing Agent POC

> **Living Document** — Keep this file updated as decisions are made, scope changes, or implementation details are confirmed. It is the source of truth for scope and KPI targets. Any KPI target, functional requirement, or constraint change should be reflected here before the next demo rehearsal.

**Document Version:** 1.5  
**Target Runtime:** JDK 21 (Virtual Threads / Project Loom), Spring Boot 3.x, Spring AI, Apache Kafka (KRaft), Debezium, PostgreSQL 16 (Snowflake Analog), Redis 7

---

## 1. Executive Summary & Business Context

A large enterprise aims to deploy an autonomous, agentic servicing solution to handle a substantial share of Tier-1 customer inquiries — including account/billing questions, ticket/request status updates, and product troubleshooting guidance. The solution must synthesize structured customer records and activity history (originating in an analytical data warehouse such as Snowflake) with unstructured knowledge (canonical policy documents, historical call transcripts, and prior case notes).

While the business sponsor's directive is broadly "reduce cost and improve customer experience," this specification establishes concrete functional requirements, non-functional guardrails, and measurable KPIs to govern scope, architecture, and delivery.

---

## 2. Success Metrics & Sponsor KPI Commitments

Every functional requirement maps directly to one or more of the following sponsor-committed KPIs:

| KPI Category | Metric Name | Baseline | Target | Architectural Lever |
|---|---|---|---|---|
| Efficiency (Cost) | True Tier-1 Deflection / Containment Rate (zero re-contact within 72h) | 12% (Legacy IVR/Bot) | 35–45% | Real-time Redis state hydration via Kafka CDC + Hybrid BM25/Vector Policy RAG resolves multi-step queries in one session |
| Efficiency (Agent) | Escalated Average Handle Time (AHT) | 11.5 mins | −25% (~8.6 mins) | Automated compilation of a structured JSON Agent Handoff Summary (account snapshot, root-cause hypothesis, attempted steps, policy citations) |
| Quality & Risk | Policy Compliance & Groundedness Rate | N/A | >98% compliance / <1% hallucination | Strict separation of probabilistic LLM reasoning from deterministic Java business rule engines + mandatory `[doc_id]` citation enforcement |
| Customer Experience | Time-to-First-Token (TTFT) / End-to-End Turn Latency | 4.8s (direct warehouse query) | <1.5s P95 | Decoupling analytical warehouse latency via Debezium/Kafka CDC into sub-millisecond Redis L1 cache + JDK 21 Virtual Threads for concurrent sessions. Prompt caching of the static prompt prefix (FR-3.5) and one model call per turn (FR-3.8). Measured with a live model (Gemini 3.1 Flash-Lite): 1.4 to 2.7 s per turn, so <1.5s P95 is met only by the offline planner today; streaming is the next lever |
| Efficiency (LLM Cost) | Input-token cost per turn | Full price on every turn | Projected 50-75% lower on turns 2+; to be measured against the real provider (FR-3.7) | Prompt caching: static prefix (persona, tool schemas, policy digest, few-shot) cached, dynamic suffix computed per turn |
| Reliability | Event Processing Integrity & Availability | N/A | Zero duplicate state mutations | Redis SETNX event deduplication + monotonic LSN ordering + non-blocking Kafka retries with Dead Letter Topics (`.DLT`) |

---

## 3. Functional Requirements (EARS Notation)

### Requirement 1: Analytical Warehouse CDC Ingestion & Operational Caching

**FR-1.1:** WHEN a row is inserted or updated in the upstream warehouse tables (`CUSTOMERS`, `BILLING_LEDGER`, `SUPPORT_TICKETS`), THE SYSTEM SHALL capture the write-ahead log (WAL) delta via Debezium and publish a structured CDC event to the corresponding Apache Kafka topic (`db.public.customers`, `db.public.billing_ledger`, `db.public.support_tickets`) within 500ms.

**FR-1.2:** WHEN the Spring Boot `@KafkaListener` receives a CDC event, THE SYSTEM SHALL verify idempotency before mutating operational state by checking Redis (`SET processed:event:{event_id} 1 NX EX 86400`) and confirming that the incoming `event_lsn` is strictly greater than the `last_applied_lsn` stored on the cached entity.

**FR-1.3:** IF a CDC event fails deserialization or downstream Redis persistence due to a transient error, THE SYSTEM SHALL execute 3 non-blocking retries with exponential backoff (`1s → 2s → 4s`) and, upon exhaustion, route the message with original exception headers to `<topic>.DLT` without blocking partition consumption.

**FR-1.4:** THE SYSTEM SHALL expose a simulation endpoint (`POST /api/demo/mutate`) allowing presenters to insert or update rows in the mock warehouse database during live demonstrations to trigger real-time CDC synchronization.

**FR-1.5 (Complete profile regardless of event order):** WHEN the CDC consumer receives a customer, billing or ticket event for a customer whose Redis profile is missing or incomplete (no customer row applied yet, e.g. a ticket event delivered before the customer event, or an event after the profile was evicted), THE SYSTEM SHALL build the full profile from all three warehouse tables with one bounded lookup before applying further incremental events, and SHALL NOT serve an incomplete profile to the agent (it is treated as a cache miss). IF the warehouse lookup fails, THEN the event SHALL be retried through the retry topics instead of writing an incomplete profile.

---

### Requirement 2: Ingress Guardrails & PII Sanitization

**FR-2.1:** WHEN a customer message arrives at `POST /api/agent/chat`, THE SYSTEM SHALL execute a deterministic PII masking filter (redacting SSNs, payment card numbers, and raw API keys) prior to logging, telemetry emission, or LLM prompt assembly.

**FR-2.2:** THE SYSTEM SHALL validate `customerId` and input payload bounds before invoking the Spring AI reasoning loop, returning an HTTP 400 Bad Request with structured error metadata for malformed requests.

---

### Requirement 3: Autonomous Agentic Orchestration & Tool Selection (Spring AI)

**FR-3.1:** THE SYSTEM SHALL utilize Spring AI's `ChatClient` registered with typed `@Tool` methods (`getCustomerState`, `searchPoliciesAndCases`, `evaluateWaiverRules`, `escalateWithSummary`) so the LLM dynamically plans and chains tool invocations based on conversational intent.

**FR-3.2:** WHEN a user inquires about account balances, unexpected charges, or ticket statuses, THE SYSTEM SHALL retrieve live customer context from the Redis L1 operational cache (<5ms lookup) rather than querying the analytical warehouse synchronously.

**FR-3.3:** WHEN executing multiple independent read tools within a turn, THE SYSTEM SHALL leverage JDK 21 Virtual Threads (Project Loom) to avoid platform thread starvation under concurrent load.

**FR-3.4:** IF a customer profile is absent from the Redis L1 cache (e.g. a dormant account), THE SYSTEM SHALL perform a bounded, indexed single-customer point lookup against the warehouse, return that snapshot to the agent, and asynchronously re-hydrate Redis without overwriting newer CDC-applied state, keeping end-to-end turn latency within the <1.5s P95 target.

**FR-3.5 (Prompt Caching, scope addition):** THE SYSTEM SHALL partition every LLM request into a **static prefix** (servicing-agent persona and tone, `@Tool` schemas and descriptions, a canonical policy digest, few-shot reasoning examples) followed by a **dynamic suffix** (customer profile, conversation history, latest customer message), and SHALL place the provider cache breakpoint immediately after the static prefix so that turns 2+ of a session reuse the cached prefix. Turn 1 writes the cache; later turns read it. Provider mechanics differ: Anthropic/Bedrock needs an explicit `cache_control: {"type": "ephemeral"}` marker, while OpenAI caches an exact prefix automatically above a minimum length (about 1,024 tokens) with no marker.

**FR-3.6:** THE SYSTEM SHALL keep all customer-specific or time-varying values (`customerId`, `accountTier`, balance, current time) out of the static prefix, because any change inside the prefix invalidates the cache. Static prefix content (prompt text, tool signatures, policy digest) is versioned and changes only through a deploy, and tool registration order SHALL be stable.

**FR-3.7:** THE SYSTEM SHALL record cache-write and cache-read token counts from the provider's usage metadata on each turn and report cache hit rate and input-token cost in `GET /api/agent/reports/kpis`, so the cost and TTFT claims are measured rather than assumed.

> **Status (2026-11-20): implemented; savings not measurable on the tested provider.** `StaticPromptPrefix` builds one byte-stable system message per orchestration mode from `agent.prompts.*`, a policy digest and (agentic mode) few-shot examples in `src/main/resources/prompts/`. Customer data goes only in the user message. `CacheUsageRecorder` adds `llmCalls`, `promptTokens`, `cachedPromptTokens` and `promptCacheHitRatePercent` to the KPI report, and `scripts/demo.ps1 caching` shows per-turn round trips, latency and tokens. `AGENT_PROMPT_CACHING=false` sends the short prompt for an A/B comparison.
>
> **Limits.** Gemini's OpenAI-compatible endpoint does not report cached tokens, so the hit rate reads 0% there; OpenAI (which reports them) was not run because the test account had no credits. No Anthropic/Bedrock `cache_control` marker is wired. Cost and TTFT savings remain projections. Details: `design.md` §4.6.

**FR-3.8 (Single model call per turn):** WHEN the agent runs with a live model, THE SYSTEM SHALL by default execute the tool plan deterministically in Java and make exactly one model call, with no tools registered, to write the reply (`agent.llm.orchestration=single-pass`). THE SYSTEM SHALL bound that call with a timeout (`agent.llm.synthesis.timeout-ms`, default 8 s) and SHALL return the deterministic draft instead of the model reply if the call times out, fails, is truncated, cites an id retrieval did not return, drops the draft's citations, or claims an approval the rule engine did not give. Model-driven tool calling (FR-3.1) remains available as `agentic`.

> **Rationale (measured 2026-11-20, Gemini, containerized stack):** agentic mode made 2 to 5 sequential model calls per turn: 19 to 105 s on `gemini-3.1-pro-preview` and 3.6 to 5.9 s on `gemini-3.1-flash-lite`. Single-pass on Flash-Lite took 1.4 to 2.7 s with one call. The tools themselves took under 40 ms.

**FR-3.9 (Provider rate-limit resilience):** THE SYSTEM SHALL retry provider HTTP 429 and 503 responses at most 3 times with exponential backoff (1 s, 2 s), and the demo scripts SHALL support pacing between chat calls (`CHAT_PACE_SECONDS`) and one delayed retry of a provider-rejected turn (`CHAT_RETRY_WAIT_SECONDS`), so live demos stay within provider rate limits. Quota exhaustion (no credits, free-tier daily limits) is reported as a failed or fallback turn and is not retried indefinitely.

---
**FR-3.10 (Multi-step investigation):** WHEN a customer asks an open billing question that names no fee or error code (e.g. "why is my bill so high?"), THE SYSTEM SHALL investigate in sequence: (1) unpaid non-subscription charges from the ledger, (2) open tickets, including the existing-ticket check (FR-6.3), (3) late-fee waiver eligibility WITHOUT applying a waiver or writing an audit event, (4) the SLA-credit policy when an outage ticket is open, and (5) verify that every amount, invoice, ticket id and citation in the answer comes from a tool result. Each step SHALL appear in `agentReasoningTrace`. IF verification fails, THEN THE SYSTEM SHALL escalate instead of answering. In single-pass LLM mode, a rewrite that contains a dollar amount not present in the verified draft SHALL be rejected (`UNVERIFIED_AMOUNT`).

---

### Requirement 4: Hybrid Knowledge Retrieval & Conflict Resolution

**FR-4.1:** WHEN searching unstructured knowledge (`searchPoliciesAndCases`), THE SYSTEM SHALL execute a hybrid retrieval query combining dense vector semantic similarity with BM25 lexical keyword matching to ensure exact recall on alphanumeric error codes (e.g., `ERR-504-GW`), SKUs, and policy numbers.

**FR-4.2:** THE SYSTEM SHALL apply metadata pre-filtering (`account_tier`, `is_active_policy = true`) and authority weighting so canonical Markdown policy documents strictly outrank historical call transcripts and legacy agent case notes.

**FR-4.3:** WHERE a historical case note contradicts an active canonical policy document, THE SYSTEM SHALL instruct the synthesis layer to follow the active canonical policy and explicitly note that legacy guidance has been superseded.

---

### Requirement 5: Deterministic Business Rule & Eligibility Guardrails

**FR-5.1:** THE SYSTEM SHALL NOT delegate financial eligibility calculations, fee waiver approvals, or SLA credit math to LLM probabilistic generation.

**FR-5.2:** WHEN a customer requests a late-fee waiver or billing adjustment, THE SYSTEM SHALL invoke the deterministic Java service `DeterministicRules.evaluateLateFeeWaiver(CustomerProfile, BigDecimal)` which enforces:
- Fee amount ≤ $50.00
- Customer `accountTier` is `Gold` or `Enterprise`
- Customer `tenureMonths` ≥ 12
- Customer `waiversUsedLast12Months` = 0

**FR-5.3:** THE SYSTEM SHALL emit a structured `WaiverEvaluationResult(boolean eligible, String reasonCode, String policyReference)` back to the LLM context so the agent communicates an auditable, policy-backed decision.

---

### Requirement 6: Sentiment-Aware Escalation & Structured Human Handoff

**FR-6.1:** WHEN the agent detects high customer frustration/churn risk, an SLA breach on an engineering-blocked ticket, or retrieval confidence below 0.72, THE SYSTEM SHALL halt autonomous troubleshooting loops and invoke `escalateWithSummary`.

**FR-6.2:** THE SYSTEM SHALL compile a structured `AgentHandoffCard` JSON payload containing:
- Verified `CustomerProfile` snapshot (Tier, Tenure, MRR, Open Tickets)
- Classified intent and detected urgency/sentiment score
- Chronological list of tools executed and facts verified
- Recommended next action and cited policy clauses for the Tier-2 human agent

**FR-6.3 (Existing-ticket recognition):** WHEN a customer reports a problem or complaint AND they already have an open (not `RESOLVED` or `CLOSED`) ticket for the same problem, THE SYSTEM SHALL treat the message as a follow-up on that ticket: the reply SHALL name the ticket id and status and SHALL NOT suggest opening a new ticket, and the trace SHALL show `findMatchingOpenTicket -> EXISTING_TICKET <id> (<reason>)`. "Same problem" is decided deterministically (`TicketMatchTools`): the message names the ticket id, an error code in the message appears in the ticket subject, or the message is about the same topic as the ticket category (technical fault or billing dispute). Waiver requests and policy questions SHALL NOT match on topic alone.

**FR-6.4 (No duplicate tickets):** WHEN a ticket is created for a customer who already has an open ticket for the same problem (shared error code, same category and subject ignoring case and punctuation, or same category family and topic), THE SYSTEM SHALL NOT insert a new ticket and SHALL return the existing ticket id with `duplicate=true` and the match reason. The check SHALL read the warehouse (not the Redis cache, which can lag behind CDC) and SHALL be serialised per customer so concurrent requests cannot both create a ticket.

**FR-6.5 (Ticket status inquiry):** WHEN a customer asks about their ticket ("status of my ticket", "any update on my ticket", or a ticket id), THE SYSTEM SHALL list their open tickets (or the named one) with subject, status and SLA time left or hours breached, and SHALL escalate a breached ticket per FR-6.1. In agent planner mode, IF the model's plan does not run the escalation check, THEN THE SYSTEM SHALL run it after the turn, so escalation never depends on the model.

---

### Requirement 7: Backend-Only Observability, Traceability & Executive Reporting

**FR-7.1:** THE SYSTEM SHALL return a comprehensive JSON `AgentTurnReport` from `POST /api/agent/chat` containing the conversational response, inline `[doc_id]` citations, ordered `agentReasoningTrace` steps (with per-tool latency in milliseconds), `deflectionStatus`, and any generated `handoffCard`.

**FR-7.2:** THE SYSTEM SHALL expose an executive analytics endpoint (`GET /api/agent/reports/kpis`) and an internal `@Tool` (`getDeflectionReport`) aggregating session counts, autonomous deflection rate, estimated AHT minutes saved, policy compliance score, and Kafka `.DLT` message depth.

**FR-7.3:** THE SYSTEM SHALL expose OpenAPI 3 / Swagger UI at `/swagger-ui.html` so all endpoints, CDC mutations, agent reasoning traces, and KPI reports can be demonstrated directly without requiring a separate frontend application.

**FR-7.4:** THE SYSTEM SHALL log one line when an agent turn starts and one when it finishes (status, latency, tools, citations, LLM round trips and model time), and one line per model call (HTTP status, duration, provider token usage), each tagged with the session and customer id, to the container console. Each model call SHALL also appear in `agentReasoningTrace` as `LLM_ROUND_TRIP #n` with its latency.

**FR-7.5 (CDC consume logging):** WHEN the CDC consumer applies a customer, billing or ticket event to Redis, THE SYSTEM SHALL log one INFO line (`CDC applied table=... id=... created|updated op=... lsn=... topic=... offset=...`) tagged with the customer id. The line SHALL contain ids only, never names, amounts, descriptions or the payload. Skipped events (duplicate, stale LSN) keep their existing log lines.

---

### Requirement 8: Human-in-the-Loop (HITL) Supervisor Overrides & OAuth2 Security

**FR-8.1:** THE SYSTEM SHALL protect all administrative endpoints (`/api/admin/**`) using Spring Security OAuth2 Resource Server validating JWT bearer tokens and enforcing scope-based RBAC (`@PreAuthorize("hasAuthority('SCOPE_servicing.supervisor')")`).

**FR-8.2:** WHEN an authorized supervisor submits a policy exception via `POST /api/admin/overrides/waiver`, THE SYSTEM SHALL apply the discretionary adjustment, update the operational state store, and record an immutable audit entry capturing the supervisor's JWT `sub`, justification, and timestamp.

**FR-8.3:** THE SYSTEM SHALL expose an administrative replay endpoint (`POST /api/admin/kafka/dlt/replay`) restricted to `SCOPE_servicing.supervisor` allowing operators to inspect and re-publish failed events from `*.DLT` topics after remediation.

---

## 4. Non-Functional Requirements & Operational Constraints

**NFR-1 (Offline Portability):** The entire multi-container stack (`compose.yaml`) must boot and execute all 3 core demonstration scenarios locally on a developer laptop without external internet dependencies (supporting local Ollama models or deterministic replay fallback alongside cloud LLM providers).

**NFR-2 (Container Resource Footprint):** All containers (PostgreSQL 16, Kafka KRaft, Debezium Connect, Redis 7, Spring Boot 3.x) must run comfortably within 16GB host RAM and start cleanly within 60 seconds via a single `docker compose up --build` command.

**NFR-3 (Auditability & Clean Code):** All prompt templates, deterministic rule thresholds, and retrieval parameters must be externalized in `application.yml` and Markdown policy files rather than hardcoded inside controllers.

**NFR-4 (Enterprise Cloud Security & LLMOps Governance):** Production deployment targets private VPC subnets on AWS EKS (or GCP GKE) with AWS PrivateLink to Snowflake, mTLS across Kafka brokers, KMS CMK encryption at rest, EKS IRSA for secretless IAM access, and Zero-Data-Retention (ZDR) Bedrock/Vertex AI endpoints. Prompt and model changes are gated in CI/CD via automated golden-dataset regression evaluations (MLflow Evaluate / Spring AI Evaluators) measuring tool-selection accuracy, guardrail adherence, and citation groundedness.

**NFR-5 (Latency SLA per operating mode):** THE SYSTEM SHALL meet a per-turn latency SLA for each mode: deterministic 1.5 s; LLM mode (single-pass) 16 s, enforced by a 15 s synthesis timeout that returns the deterministic draft; agent planner mode (agentic) 60 s. The sponsor KPI of <1.5 s P95 applies to deterministic mode; streaming the reply is the lever for the LLM modes. Design: `design.md` §4.9.

---

## 5. Out-of-Scope (Phase 1 POC Boundaries)

- **Direct write-back mutations** to external production billing systems (waiver approval is recorded in the operational state store and emitted as an auditable action event).
- **Custom React/SPA frontend** development (intentionally omitted in favor of Swagger UI, structured JSON trace reports, and container console telemetry).
- **Multi-language translation and voice/telephony SIP trunk integration** (deferred to Phase 2 roadmap).
- **Dedicated decision model (`gemini-decision-1`)** for pre-turn safety screening, intent and escalation triage, and RAG chunk grading. Deferred to the production scale-up path; the POC uses one generative model plus deterministic Java guardrails (rationale: design.md §6).
- **Local MLflow tracking server container** inside `compose.yaml` (LLMOps regression evaluation runs as a CI/CD pipeline gate rather than a runtime chat dependency).


---

## 6. Future Requirements (Production Scale-Up, NOT in Phase 1)

Status: planned, not implemented, not part of the POC acceptance criteria. Design: `design.md` §9. These split fast classification from generation by adding a dedicated decision model (candidate: `gemini-decision-1`, probed on the demo set; see design.md §6) in front of the generative model.

| ID | Requirement (EARS) | KPI lever |
|---|---|---|
| FR-F1 | WHEN a customer message arrives AND the decision gateway is enabled, THE system SHALL score it for prompt injection and off-topic content after PII sanitization and before any generative call, AND SHALL not call the generative model for a message scored above the block threshold. | Quality & Risk |
| FR-F2 | WHEN a message passes screening, THE system SHALL score it against a fixed set of intents (for example `BILLING_WAIVER`, `TICKET_STATUS`, `FRUSTRATION_ESCALATE`) AND SHALL use the top intent to select the Java tool plan; a top score below the configured threshold SHALL trigger escalation. | Deflection, AHT |
| FR-F3 | WHEN retrieval returns candidate chunks, THE system SHALL grade each against a rubric (`is_superseded`, `directly_answers_query`) in one call AND SHALL pass only chunks that meet the thresholds to the generative model. | Groundedness |
| FR-F4 | IF the decision gateway errors or exceeds its timeout, THEN THE system SHALL fall back to the POC behaviour (PII masking only, keyword tool plan, authority-weighted retrieval) AND SHALL record the fallback in the trace and KPI report. | Reliability |
| FR-F5 | THE system SHALL support a shadow mode in which the decision model is called and logged on every turn but does not change the outcome, so agreement with the deterministic path can be measured before a check goes live. | Quality & Risk |
| FR-F6 | THE system SHALL NOT let a decision-model score approve a waiver, credit or any financial outcome; eligibility remains in deterministic Java (FR-5.1 to FR-5.3). | Quality & Risk |

Non-functional targets (to be confirmed by measurement): added latency per gated turn about 300 ms (measured mean 295 ms per call on 2026-11-21, design.md §6); each check has a configurable timeout (initial 500 ms); the default configuration works offline with no decision model (NFR-1).

---

## Changelog

| Date | Author | Change |
|---|---|---|
| 2025-07-14 | Initial | Spec created from source documents (requirements.md, presentation_talking_points.md) |
| 2026-11-17 | Update | Synchronized from source docs; added Living Document header, refined `event_lsn` check in FR-1.2, added Changelog |
| 2026-11-18 | Update | Added FR-8 (OAuth2-protected HITL Supervisor Overrides & DLT Replay), NFR-4 (AWS EKS/VPC Defense-in-Depth & CI/CD MLflow Evaluation), MLflow local container out-of-scope boundary |
| 2026-11-18 | Update | Added FR-3.4 (Asymmetric Cache-Miss Fallback for dormant accounts), per design.md v1.3; version 1.3 |
| 2026-11-19 | Update | Added FR-3.5 to FR-3.7 (Prompt Caching: static prefix / dynamic suffix, cache-safe prompt assembly, cache metrics) as a scope addition, a projected LLM-cost KPI row and TTFT lever; version 1.4, per design.md §4.6 |
| 2026-11-19 | Implementation | FR-3.5 to FR-3.7 implemented (static prefix, usage metrics, A/B switch); savings unmeasured; OpenAI automatic caching only |
| 2026-11-20 | Update | v1.5: FR-3.8 (single model call per turn, timeout and draft fallback), FR-3.9 (bounded provider retries, script pacing), FR-7.4 (turn and LLM-call logging); FR-3.5 status updated with live Gemini findings; latency lever annotated with measured numbers. Per design.md v1.6 §4.7, §4.8 |
| 2026-11-20 | Fix | Restored Requirements 4 to 8, accidentally removed by an edit on 2026-11-19, from the v1.3 text (unchanged since) |
| 2026-11-19 | Update | Compose file name (`compose.yaml`); implementation status of every requirement is tracked in design.md §8 |
| 2026-11-21 | Scope | Added decision-model screening/triage/grading to Out-of-Scope as a production scale-up item, per design.md §6. Added §6 Future Requirements (FR-F1 to FR-F6, planned only), per design.md §9 |
| 2026-11-23 | Scope | Added FR-6.5 (ticket status inquiry; escalation check enforced by Java in agent planner mode); implemented, verified per design.md §8 |
| 2026-11-21 | Scope | Added FR-3.10 (multi-step billing investigation with fact verification); implemented, verified per design.md §8 |
| 2026-11-21 | Scope | Added FR-1.5 (complete profile regardless of CDC event order or eviction); implemented, verified in tests per design.md §8 | (existing-ticket recognition), FR-6.4 (no duplicate tickets for the same problem) and FR-7.5 (CDC consume logging); implemented and verified per design.md §8 |

> **How to use this changelog:** Add a row every time a requirement is added, removed, or its target/threshold changes. Include the reason (e.g., "Panel feedback: tightened deflection target to 40% minimum after Q&A rehearsal").
