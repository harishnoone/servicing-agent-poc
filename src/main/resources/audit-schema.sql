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
