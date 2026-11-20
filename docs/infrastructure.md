# Infrastructure & CDC Pipeline Setup

> **Living Document** — This file contains the exact, runnable configuration for the 5-container local Docker environment. Update it whenever a container image version changes, an environment variable is added, the schema evolves, or a new demo persona is introduced. Changes here that affect the demo walkthrough must also be reflected in `playbook.md`.

**Stack:** PostgreSQL 16 (logical replication) · Apache Kafka 3.7 (KRaft) · Debezium Connect 2.6 · Redis 7 · Spring Boot 3.x (JDK 21)

---

## 1. `compose.yaml`

Single command to boot the full stack: `docker compose up --build` (file: `compose.yaml` at the repository root). Stop with `docker compose stop` (keeps data) or `docker compose down -v` (wipes volumes, re-runs `init.sql` next start).

> **Excerpt.** The YAML below shows the essentials. `compose.yaml` is authoritative: it also has longer healthcheck retries (10), the Kafka healthcheck on the internal listener `localhost:29092`, `KAFKA_AUTO_CREATE_TOPICS_ENABLE`, an app healthcheck, and the app's dependency on `debezium`.

```yaml
services:
  warehouse-db:
    image: postgres:16-alpine
    container_name: warehouse-db
    environment:
      POSTGRES_USER: warehouse_admin
      POSTGRES_PASSWORD: warehouse_password
      POSTGRES_DB: enterprise_warehouse
    ports:
      - "5432:5432"
    command: ["postgres", "-c", "wal_level=logical", "-c", "max_wal_senders=4", "-c", "max_replication_slots=4"]
    volumes:
      - ./src/main/resources/init.sql:/docker-entrypoint-initdb.d/init.sql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U warehouse_admin -d enterprise_warehouse"]
      interval: 5s
      timeout: 5s
      retries: 5

  kafka:
    image: apache/kafka:3.7.0
    container_name: kafka
    ports:
      - "9092:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: INTERNAL://:29092,EXTERNAL://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:29092,EXTERNAL://localhost:9092
      KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
    healthcheck:
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --list"]
      interval: 10s
      timeout: 5s
      retries: 5

  debezium:
    image: debezium/connect:2.6
    container_name: debezium
    depends_on:
      warehouse-db:
        condition: service_healthy
      kafka:
        condition: service_healthy
    ports:
      - "8083:8083"
    environment:
      BOOTSTRAP_SERVERS: kafka:29092
      GROUP_ID: 1
      CONFIG_STORAGE_TOPIC: connect_configs
      OFFSET_STORAGE_TOPIC: connect_offsets
      STATUS_STORAGE_TOPIC: connect_statuses
      CONFIG_STORAGE_REPLICATION_FACTOR: 1
      OFFSET_STORAGE_REPLICATION_FACTOR: 1
      STATUS_STORAGE_REPLICATION_FACTOR: 1

  redis:
    image: redis:7-alpine
    container_name: redis
    ports:
      - "6379:6379"
    command: ["redis-server", "--appendonly", "yes"]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5

  servicing-agent-app:
    build: .
    container_name: servicing-agent-app
    depends_on:
      warehouse-db:
        condition: service_healthy
      kafka:
        condition: service_healthy
      redis:
        condition: service_healthy
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://warehouse-db:5432/enterprise_warehouse
      SPRING_DATASOURCE_USERNAME: warehouse_admin
      SPRING_DATASOURCE_PASSWORD: warehouse_password
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:29092
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PORT: 6379
      DEBEZIUM_CONNECT_URL: http://debezium:8083
      OPENAI_API_KEY: ${OPENAI_API_KEY:-placeholder}   # unset = offline deterministic planner (NFR-1)
      AGENT_LLM_MODE: ${AGENT_LLM_MODE:-auto}
      SPRING_AI_OPENAI_BASE_URL: ${OPENAI_BASE_URL:-https://api.openai.com}            # Gemini: https://generativelanguage.googleapis.com/v1beta/openai
      SPRING_AI_OPENAI_CHAT_COMPLETIONS_PATH: ${OPENAI_COMPLETIONS_PATH:-/v1/chat/completions}   # Gemini: /chat/completions
      SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL: ${OPENAI_MODEL:-gpt-4o}
```

### Key Design Decisions in This Compose File

| Decision | Detail | Why |
|---|---|---|
| `wal_level=logical` | Passed as a Postgres command-line flag, not via config file | Required for Debezium WAL replication — must be set at startup |
| `max_replication_slots=4` | Allows multiple connector restarts without slot exhaustion | Prevents `ERROR: all replication slots are in use` during demo restarts |
| `healthcheck` on all upstream services | Debezium and the Spring app use `condition: service_healthy` | Prevents Debezium from starting before Postgres WAL is ready, which causes connector registration to fail silently |
| `appendonly yes` on Redis | Enables Redis AOF persistence | Survives container restarts during demo without losing cached customer profiles |
| `DEBEZIUM_CONNECT_URL` env var | Injected into Spring app | `DebeziumConnectorRegistrar` registers the CDC connector on startup (idempotent `PUT /connectors/{name}/config`, retried while Connect boots) |
| Two Kafka listeners | `kafka:29092` for containers, `localhost:9092` for the host | Lets the app run either in Compose or from the IDE against the same broker |
| `customer_id` indexes | See `init.sql` | Keep the cache-miss fallback a bounded point lookup |

---

## 2. `src/main/resources/init.sql`

Snowflake-analog schema with `REPLICA IDENTITY FULL` on all three tables (required for Debezium to emit complete before/after row states, not just changed columns).

```sql
-- Tables
CREATE TABLE customers (
    customer_id      VARCHAR(32)    PRIMARY KEY,
    full_name        VARCHAR(120)   NOT NULL,
    account_tier     VARCHAR(20)    NOT NULL CHECK (account_tier IN ('Standard', 'Gold', 'Enterprise')),
    tenure_months    INT            NOT NULL,
    mrr              DECIMAL(10, 2) NOT NULL,
    waivers_used_12m INT            NOT NULL DEFAULT 0,
    status           VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    updated_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE customers REPLICA IDENTITY FULL;

CREATE TABLE billing_ledger (
    invoice_id   VARCHAR(32)    PRIMARY KEY,
    customer_id  VARCHAR(32)    REFERENCES customers(customer_id),
    charge_type  VARCHAR(30)    NOT NULL,  -- 'SUBSCRIPTION', 'LATE_FEE', 'OVERAGE'
    amount       DECIMAL(10, 2) NOT NULL,
    status       VARCHAR(20)    NOT NULL,  -- 'PAID', 'UNPAID', 'WAIVED'
    description  TEXT,
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE billing_ledger REPLICA IDENTITY FULL;

CREATE TABLE support_tickets (
    ticket_id    VARCHAR(32)  PRIMARY KEY,
    customer_id  VARCHAR(32)  REFERENCES customers(customer_id),
    category     VARCHAR(40)  NOT NULL,
    subject      TEXT         NOT NULL,
    status       VARCHAR(30)  NOT NULL,  -- 'OPEN', 'PENDING_ENGINEERING', 'RESOLVED'
    sla_deadline TIMESTAMP WITH TIME ZONE NOT NULL,
    last_updated TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE support_tickets REPLICA IDENTITY FULL;
```

### Demo Personas

Three pre-seeded personas cover all three core demo scenarios (`playbook.md` section 2). Full seed rows (including tickets and late fees) are in `src/main/resources/init.sql`; the SQL below shows the essentials.

#### Persona 1 — Elena Vance (`cust_123`)
**Scenario:** Automated late-fee waiver (Demo Step 1 + 2)

```sql
INSERT INTO customers (customer_id, full_name, account_tier, tenure_months, mrr, waivers_used_12m, status)
VALUES ('cust_123', 'Elena Vance', 'Gold', 36, 499.00, 0, 'ACTIVE');

INSERT INTO billing_ledger (invoice_id, customer_id, charge_type, amount, status, description)
VALUES ('inv_9001', 'cust_123', 'SUBSCRIPTION', 499.00, 'PAID', 'Monthly Enterprise Platform Fee');
```

**Why she resolves autonomously:**
- Account tier: `Gold` ✓ (rule: Gold or Enterprise)
- Tenure: 36 months ✓ (rule: ≥ 12 months)
- Waivers used: 0 ✓ (rule: = 0 in last 12 months)
- Late fee: $45.00 ✓ (rule: ≤ $50.00)
- All 4 `DeterministicRulesTools` conditions pass → `eligible = true`

#### Persona 2 — Marcus Sterling (`cust_456`)
**Scenario:** SLA breach escalation + ERR-504-GW conflict resolution (Demo Step 3)

```sql
INSERT INTO customers (customer_id, full_name, account_tier, tenure_months, mrr, waivers_used_12m, status)
VALUES ('cust_456', 'Marcus Sterling', 'Enterprise', 48, 2400.00, 1, 'ACTIVE');

INSERT INTO support_tickets (ticket_id, customer_id, category, subject, status, sla_deadline)
VALUES ('TKT-8841', 'cust_456', 'GATEWAY_OUTAGE', 'Production API Gateway throwing ERR-504-GW',
        'PENDING_ENGINEERING', NOW() - INTERVAL '4 hours');
```

**Why he escalates:**
- Ticket status: `PENDING_ENGINEERING` (blocked, agent cannot resolve)
- SLA deadline: 4 hours **past** — breach already occurred
- `waivers_used_12m = 1` — ineligible for a second waiver even if requested
- Agent invokes `escalateWithSummary` → compiles `AgentHandoffCard` with the ticket snapshot, the SLA-breach step ("Ticket TKT-8841 … passed SLA deadline …") and the cited policies; the reply cites the 2026 fix and calls the 2024 case superseded

#### Persona 3 - Jordan Blake (`cust_789`)
**Scenario:** Clean waiver denial without escalation

Standard tier, 6 months tenure, unpaid $25.00 late fee (`INV-2025-789-001`). `evaluateWaiverRules` returns `TIER_INELIGIBLE`; the turn is `RESOLVED_AUTONOMOUSLY` with an explained denial.

#### Cache-miss demo (any persona)
`DELETE /api/demo/cache/cust_123` removes the Redis profile. The next `POST /api/agent/chat` shows `TOOL_CALL: getCustomerState (CACHE_MISS -> WAREHOUSE_FALLBACK)` in the trace, and Redis is re-hydrated in the background.

---

## 3. `connector-config/register-postgres-cdc.json`

The app registers this connector itself on startup (`DebeziumConnectorRegistrar` reads the identical copy in `src/main/resources/debezium/` and PUTs it to Kafka Connect). Manual fallback: `PUT http://localhost:8083/connectors/warehouse-postgres-connector/config` with the `config` object.

```json
{
  "name": "warehouse-postgres-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "database.hostname": "warehouse-db",
    "database.port": "5432",
    "database.user": "warehouse_admin",
    "database.password": "warehouse_password",
    "database.dbname": "enterprise_warehouse",
    "topic.prefix": "db",
    "table.include.list": "public.customers,public.billing_ledger,public.support_tickets",
    "plugin.name": "pgoutput",
    "slot.name": "servicing_slot",
    "publication.name": "servicing_publication",
    "publication.autocreate.mode": "filtered",
    "key.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
    "key.converter.schemas.enable": "false",
    "value.converter.schemas.enable": "false",
    "decimal.handling.mode": "string",
    "tombstones.on.delete": "false"
  }
}
```

### Key Config Decisions

| Field | Value | Why |
|---|---|---|
| `plugin.name: pgoutput` | Built-in Postgres 10+ logical decoding plugin | No separate Postgres plugin install required — works on `postgres:16-alpine` out of the box |
| `slot.name: servicing_slot` | Named replication slot | Named slots survive Debezium restarts and resume from last confirmed LSN — no event loss on demo restart |
| `publication.autocreate.mode: filtered` | Only publishes `table.include.list` tables | Prevents Debezium from creating a publication for all tables, which would require superuser in some Postgres configs |
| `decimal.handling.mode: string` | Serializes `DECIMAL` as a JSON string | Exact `BigDecimal` money values; `double` would introduce binary rounding on `amount`/`mrr` |
| `value.converter.schemas.enable: false` | Plain JSON envelope | Smaller messages; the consumer also unwraps `{schema, payload}` if schemas are enabled |
| `tombstones.on.delete: false` | Suppresses null tombstone messages on DELETE | Keeps the Kafka topic clean — the agent only needs INSERT/UPDATE events, not delete tombstones |

---

## Startup Sequence

```
1. docker compose up --build -d          (or: scripts/demo.ps1 reset for a clean stack)
   └── warehouse-db starts → healthcheck passes (pg_isready)
   └── kafka starts → healthcheck passes (kafka-topics.sh --list)
   └── debezium starts → depends on warehouse-db + kafka healthy
   └── redis starts → healthcheck passes (redis-cli ping)
   └── servicing-agent-app starts → depends on all three healthy

2. Debezium connector is auto-registered by the app on startup
   (manual fallback: PUT http://localhost:8083/connectors/warehouse-postgres-connector/config
    with the "config" object from connector-config/register-postgres-cdc.json)
   The connector's initial snapshot loads the init.sql personas into Redis.

3. Optional: load extra test customers
   scripts/demo.ps1 seed     (or scripts/demo.sh seed) → 8 customers from scripts/seed-data.txt via CDC

4. Verify CDC is live:
   scripts/demo.ps1 charge cust_123 LATE_FEE 45   → waits until the charge is in Redis
   (or POST /api/demo/mutate, then GET /api/demo/profile/cust_123)

5. Open Swagger UI:
   http://localhost:8080/swagger-ui.html

6. Full check: powershell -File scripts/demo-smoke.ps1 → ALL CHECKS PASSED (changes demo data; reset after)
```

---

## Seed Data Beyond init.sql

`scripts/seed-data.txt` holds `POST` lines (sent to the demo endpoints, so every row flows through CDC) and `CHAT` lines (suggested questions run by `demo.ps1 scenarios`). Re-running `seed` skips existing customers. Expected outcomes:

| Customer | Setup | Outcome |
|---|---|---|
| `cust_222` Noah Patel | Gold, 18m, $20 late fee | `APPROVED` |
| `cust_321` Priya Nair | Gold, 8m, $30 late fee | `TENURE_TOO_SHORT` → escalated (policy §4.3) |
| `cust_654` Diego Alvarez | Enterprise, $75 late fee | `FEE_TOO_HIGH` |
| `cust_444` Omar Haddad | Standard, 3m | `TIER_INELIGIBLE` |
| `cust_555` Mia Rossi | Gold, 1 waiver used | `WAIVER_LIMIT_REACHED` |
| `cust_987` Sam Okafor | $120 overage, open ticket | `HIGH_FRUSTRATION` escalation |
| `cust_333` Lena Fischer | ERR-504-GW ticket 2h past SLA | `SLA_BREACH` escalation, 2026 fix cited |
| `cust_111` Ava Chen | No issues | SLA-credit answer from `sla_credit_policy.md#sec-4.1` |

Demo endpoints used by the scripts: `POST /api/demo/customers`, `POST /api/demo/tickets` (`slaHoursFromNow` < 0 = breached), `POST /api/demo/customers/{id}` (update), `GET /api/demo/profile/{id}`, `DELETE /api/demo/cache/{id}`, `POST /api/demo/poison-pill`.

---

## Future infrastructure: decision-model gateway (planned, NOT IMPLEMENTED)

Nothing here is in `compose.yaml` or `application.yml` today. See `design.md` §9 and `requirements.md` §6.

- **No new container.** The decision model is a hosted API called over HTTPS, like the generative model. The 5-container local stack is unchanged, and the default `NoOpDecisionGateway` keeps the stack working offline.
- **Planned configuration** (names are proposals):

| Setting | Purpose | Initial value |
|---|---|---|
| `agent.decision.enabled` | Turn the gateway on; off by default | `false` |
| `agent.decision.mode` | `shadow` (log only) or `enforce` | `shadow` |
| `agent.decision.base-url`, `DECISION_API_KEY` | Endpoint and secret (env var, never committed) | unset |
| `agent.decision.model` | Decision model id | `gemini-decision-1` |
| `agent.decision.timeout-ms` | Per-call timeout before fallback | `500` |
| `agent.decision.block-threshold` | Safety score above which the turn is blocked | tune on golden set |
| `agent.decision.intent-threshold` | Minimum top-intent score before escalation | tune on golden set |

- **Production deployment (extends design §7.2):** call the decision endpoint from EKS through the same egress path and Zero-Data-Retention terms as the generative model, after `PiiSanitizer`. Add a circuit breaker and a Micrometer timer and counter (`decision.calls`, `decision.fallbacks`, `decision.blocked`) so shadow-mode agreement and fallback rate can be watched before enforcement.
- **CI gate:** the golden-transcript evaluation (design §7.2) runs the gateway in shadow mode and fails the build on a regression in compliance score before any check moves to `enforce`.

---

## Changelog

| Date | Author | Change |
|---|---|---|
| 2025-07-14 | Initial | File created from `infrastructure_and_cdc_setup.md` source document |
| 2026-11-18 | Implementation | Split Kafka listeners (internal 29092 / host 9092); connector auto-registration; `decimal.handling.mode: string`; Persona 3 (Jordan Blake); cache-miss demo step and `customer_id` indexes |
| 2026-11-19 | Implementation | Compose file name and stop commands; LLM provider env vars (OpenAI/Gemini); startup sequence uses `demo.ps1 reset/seed/charge`; seed data table and demo endpoints |
| 2026-11-21 | Plan | Added "Future infrastructure: decision-model gateway" (planned config, no new container, production notes). Not implemented |
