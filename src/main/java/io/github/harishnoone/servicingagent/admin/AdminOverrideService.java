package io.github.harishnoone.servicingagent.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.harishnoone.servicingagent.model.CustomerProfile;
import io.github.harishnoone.servicingagent.model.CustomerProfile.LedgerEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies supervisor-approved discretionary waivers (FR-8.2).
 *
 * <p>The warehouse update and the audit row are written in one database transaction.
 * The change then reaches Redis through CDC; the cached profile is also patched directly
 * so the agent sees the override immediately. The patch writes the same values CDC will
 * later write, so both paths converge.
 */
@Service
public class AdminOverrideService {

    private static final Logger log = LoggerFactory.getLogger(AdminOverrideService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AuditService auditService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String profileKeyPrefix;

    public AdminOverrideService(JdbcTemplate jdbc, TransactionTemplate tx, AuditService auditService,
                                StringRedisTemplate redis, ObjectMapper objectMapper,
                                @Value("${agent.redis.profile-key-prefix}") String profileKeyPrefix) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.auditService = auditService;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.profileKeyPrefix = profileKeyPrefix;
    }

    public OverrideResult applyWaiverOverride(String supervisorSub, String customerId, String invoiceId,
                                              BigDecimal amount, String justification) {
        auditService.ensureSchema(); // DDL outside the data transaction
        Instant now = Instant.now();

        Long auditId = tx.execute(status -> {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM customers WHERE customer_id = ?", Integer.class, customerId);
            if (exists == null || exists == 0) {
                throw new NotFoundException("Customer not found: " + customerId);
            }
            if (invoiceId != null && !invoiceId.isBlank()) {
                int rows = jdbc.update(
                        "UPDATE billing_ledger SET status = 'WAIVED' WHERE invoice_id = ? AND customer_id = ?",
                        invoiceId, customerId);
                if (rows == 0) {
                    throw new NotFoundException("Invoice " + invoiceId + " not found for customer " + customerId);
                }
            }
            jdbc.update("UPDATE customers SET waivers_used_12m = waivers_used_12m + 1, updated_at = NOW() "
                    + "WHERE customer_id = ?", customerId);
            return auditService.append("WAIVER_OVERRIDE", supervisorSub, customerId, invoiceId, amount, justification);
        });

        patchRedis(customerId, invoiceId);
        log.info("Waiver override applied: customer={} invoice={} amount={} by={} auditId={}",
                customerId, invoiceId, amount, supervisorSub, auditId);
        return new OverrideResult(auditId, customerId, invoiceId, amount, supervisorSub, now, "APPLIED");
    }

    private void patchRedis(String customerId, String invoiceId) {
        try {
            String key = profileKeyPrefix + customerId;
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return; // not cached yet; CDC will hydrate the updated row
            }
            CustomerProfile p = objectMapper.readValue(json, CustomerProfile.class);
            List<LedgerEntry> charges = new ArrayList<>();
            for (LedgerEntry e : p.recentCharges()) {
                charges.add(e.invoiceId().equals(invoiceId)
                        ? new LedgerEntry(e.invoiceId(), e.chargeType(), e.amount(), "WAIVED", e.description())
                        : e);
            }
            redis.opsForValue().set(key, objectMapper.writeValueAsString(new CustomerProfile(
                    p.customerId(), p.fullName(), p.accountTier(), p.tenureMonths(), p.mrr(),
                    p.waiversUsed12m() + 1, p.lastAppliedLsn(), charges, p.activeTickets())));
        } catch (Exception e) {
            log.warn("Redis patch after override failed for {}; CDC will converge: {}", customerId, e.getMessage());
        }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }

    public record OverrideResult(Long auditId, String customerId, String invoiceId, BigDecimal amount,
                                 String supervisorSub, Instant timestamp, String status) {}
}
