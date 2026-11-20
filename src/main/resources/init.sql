-- =============================================================================
-- init.sql: schema and demo seed data for the Servicing Agent POC.
-- wal_level=logical is set on the Postgres command line in compose.yaml.
-- =============================================================================

-- 1. Schema --------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS customers (
    customer_id      VARCHAR(32)    PRIMARY KEY,
    full_name        VARCHAR(120)   NOT NULL,
    account_tier     VARCHAR(20)    NOT NULL CHECK (account_tier IN ('Standard', 'Gold', 'Enterprise')),
    tenure_months    INT            NOT NULL DEFAULT 0,
    mrr              DECIMAL(10,2)  NOT NULL DEFAULT 0.00,
    waivers_used_12m INT            NOT NULL DEFAULT 0,
    status           VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS billing_ledger (
    invoice_id   VARCHAR(32)    PRIMARY KEY,
    customer_id  VARCHAR(32)    NOT NULL REFERENCES customers(customer_id),
    charge_type  VARCHAR(30)    NOT NULL,   -- SUBSCRIPTION | LATE_FEE | OVERAGE
    amount       DECIMAL(10,2)  NOT NULL,
    status       VARCHAR(20)    NOT NULL,   -- PAID | UNPAID | WAIVED
    description  TEXT,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS support_tickets (
    ticket_id    VARCHAR(32)    PRIMARY KEY,
    customer_id  VARCHAR(32)    NOT NULL REFERENCES customers(customer_id),
    category     VARCHAR(40)    NOT NULL,
    subject      TEXT           NOT NULL,
    status       VARCHAR(30)    NOT NULL,   -- OPEN | PENDING_ENGINEERING | RESOLVED
    sla_deadline TIMESTAMPTZ    NOT NULL,
    last_updated TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

-- Indexes for the asymmetric cache-miss fallback (bounded point lookups by customer_id)
CREATE INDEX IF NOT EXISTS idx_billing_ledger_customer  ON billing_ledger (customer_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_support_tickets_customer ON support_tickets (customer_id, last_updated DESC);

-- Full before/after row images for Debezium
ALTER TABLE customers       REPLICA IDENTITY FULL;
ALTER TABLE billing_ledger  REPLICA IDENTITY FULL;
ALTER TABLE support_tickets REPLICA IDENTITY FULL;

-- Append-only audit trail (FR-8.2). Not CDC-captured.
CREATE TABLE IF NOT EXISTS audit_log (
    audit_id       BIGSERIAL      PRIMARY KEY,
    action         VARCHAR(40)    NOT NULL,
    supervisor_sub VARCHAR(200)   NOT NULL,
    customer_id    VARCHAR(32)    NOT NULL,
    invoice_id     VARCHAR(32),
    amount         DECIMAL(10,2),
    justification  TEXT           NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE OR REPLACE FUNCTION audit_log_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS audit_log_no_change ON audit_log;
CREATE TRIGGER audit_log_no_change
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_immutable();

-- 2. Demo personas ---------------------------------------------------------------

-- Persona 1: Elena Vance. Gold, 36 months, 0 waivers. Late-fee waiver resolves autonomously.
INSERT INTO customers (customer_id, full_name, account_tier, tenure_months, mrr, waivers_used_12m, status)
VALUES ('cust_123', 'Elena Vance', 'Gold', 36, 499.00, 0, 'ACTIVE')
ON CONFLICT (customer_id) DO NOTHING;

INSERT INTO billing_ledger (invoice_id, customer_id, charge_type, amount, status, description)
VALUES ('inv_9001', 'cust_123', 'SUBSCRIPTION', 499.00, 'PAID', 'Monthly Enterprise Platform Fee')
ON CONFLICT (invoice_id) DO NOTHING;

INSERT INTO support_tickets (ticket_id, customer_id, category, subject, status, sla_deadline)
VALUES ('TKT-7721', 'cust_123', 'BILLING', 'Request to review monthly invoice discrepancy',
        'OPEN', NOW() + INTERVAL '3 days')
ON CONFLICT (ticket_id) DO NOTHING;

-- Persona 2: Marcus Sterling. Enterprise, 48 months, 1 waiver already used.
-- Engineering-blocked ERR-504-GW ticket 4 hours past SLA: escalates.
INSERT INTO customers (customer_id, full_name, account_tier, tenure_months, mrr, waivers_used_12m, status)
VALUES ('cust_456', 'Marcus Sterling', 'Enterprise', 48, 2400.00, 1, 'ACTIVE')
ON CONFLICT (customer_id) DO NOTHING;

INSERT INTO billing_ledger (invoice_id, customer_id, charge_type, amount, status, description)
VALUES ('INV-2025-456-001', 'cust_456', 'LATE_FEE', 35.00, 'UNPAID', 'Late payment fee for invoice cycle 2025-01')
ON CONFLICT (invoice_id) DO NOTHING;

INSERT INTO support_tickets (ticket_id, customer_id, category, subject, status, sla_deadline)
VALUES ('TKT-8841', 'cust_456', 'GATEWAY_OUTAGE', 'Production API Gateway throwing ERR-504-GW',
        'PENDING_ENGINEERING', NOW() - INTERVAL '4 hours')
ON CONFLICT (ticket_id) DO NOTHING;

-- Persona 3: Jordan Blake. Standard, 6 months. Clean waiver denial (TIER_INELIGIBLE), no escalation.
INSERT INTO customers (customer_id, full_name, account_tier, tenure_months, mrr, waivers_used_12m, status)
VALUES ('cust_789', 'Jordan Blake', 'Standard', 6, 49.00, 0, 'ACTIVE')
ON CONFLICT (customer_id) DO NOTHING;

INSERT INTO billing_ledger (invoice_id, customer_id, charge_type, amount, status, description)
VALUES ('INV-2025-789-001', 'cust_789', 'LATE_FEE', 25.00, 'UNPAID', 'Late payment fee')
ON CONFLICT (invoice_id) DO NOTHING;
