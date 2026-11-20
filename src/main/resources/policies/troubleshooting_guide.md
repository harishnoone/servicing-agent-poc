# Platform Troubleshooting Guide — 2026 Edition

**Document ID:** troubleshooting_guide.md  
**Owner:** Platform Engineering  
**Effective Date:** 2026-01-01  
**Supersedes:** troubleshooting_guide_2024.md (see §0 for breaking changes)  

---

## 0. Breaking Changes from the 2024 Edition

> **⚠ IMPORTANT — Read before applying any resolution step.**
>
> The 2024 troubleshooting guide recommended a **firmware downgrade** as the primary fix for ERR-504-GW errors. **That approach is deprecated and must not be used.** Firmware downgrades on the edge-node fleet introduce a known memory-safety regression (CVE-2024-38817) and cause cascading failures on nodes running TLS 1.3-only configurations.
>
> The correct 2026 resolution for ERR-504-GW is **TLS certificate rotation** as described in §1.1.

---

## 1. Gateway Errors

### 1.1 ERR-504-GW — Gateway Timeout / Certificate Rotation Required

**Symptoms:**

- HTTP 504 responses from the primary edge node.
- TLS handshake failures visible in gateway access logs (`ssl_error_rx_record_too_long` or `certificate has expired`).
- Downstream services report `Connection reset by peer` on port 443.

**Root Cause (2026):**

ERR-504-GW is almost always caused by an expired or mis-issued TLS certificate on the primary edge gateway. The gateway refuses to forward requests when it cannot present a valid certificate to upstream clients, producing a timeout rather than an explicit TLS error to the caller.

**Resolution Steps:**

1. **Rotate the TLS certificate** on the affected edge node.
   - Retrieve the current certificate fingerprint: `openssl s_client -connect <gateway-host>:443 -showcerts`
   - Issue a replacement certificate from the internal CA or the configured ACME provider.
   - Deploy the new certificate to the gateway certificate store and reload the TLS listener: `systemctl reload edge-gateway`
   - Verify the new certificate is served: re-run the `openssl s_client` command and confirm the new expiry date.

2. **Flush the gateway cache** after certificate rotation.
   - Connect to the gateway admin API: `curl -X POST http://localhost:8080/admin/cache/flush -H "Authorization: Bearer $ADMIN_TOKEN"`
   - Confirm cache flush acknowledgement in the response body (`{"status":"flushed"}`).
   - Allow 30 seconds for in-flight requests to drain before proceeding.

3. **Verify end-to-end connectivity.**
   - Run the connectivity probe from an external vantage point: `curl -v https://<gateway-host>/health`
   - Confirm HTTP 200 and a valid certificate chain in the TLS handshake output.
   - Check the edge-node error log (`/var/log/edge-gateway/error.log`) for any residual TLS errors; there should be none after a successful rotation.

**Escalation:**

If ERR-504-GW persists after completing all three steps, escalate to Platform Engineering (P1 pager channel) with the output of steps 1 and 3 attached. Do **not** attempt a firmware downgrade — that approach was deprecated in 2024 and will make the situation worse.

---

## 2. Database Connectivity Errors

### 2.1 ERR-DB-CONN — PostgreSQL Connection Refused

**Symptoms:** Application logs show `Connection refused` to `warehouse-db:5432`.

**Resolution Steps:**

1. Verify the database container is running: `docker ps | grep warehouse-db`
2. Check WAL receiver status: `psql -U warehouse_admin -c "SELECT * FROM pg_stat_replication;"`
3. If the slot is inactive, restart the Debezium connector via the Kafka Connect REST API.

---

## 3. Kafka / CDC Pipeline Errors

### 3.1 Consumer Lag Alert

**Symptoms:** Grafana shows consumer-group `servicing-agent-group` lag > 1000 messages.

**Resolution Steps:**

1. Check DLT topic (`db.public.customers.DLT`, etc.) for poison-pill messages.
2. Inspect application logs for deserialization exceptions.
3. If DLT messages are present, review the `IdempotentKafkaConsumer` logs and replay after fixing the root cause.

---

## 4. Version History

| Version | Date | Change |
|---------|------|--------|
| 2026.1 | 2026-01-01 | Deprecated firmware-downgrade approach for ERR-504-GW; replaced with TLS certificate rotation steps (§1.1) |
| 2024.3 | 2024-11-01 | (Superseded) ERR-504-GW: firmware downgrade workaround — DO NOT USE |
