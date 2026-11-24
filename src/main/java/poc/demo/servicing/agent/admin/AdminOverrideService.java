package poc.demo.servicing.agent.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import poc.demo.servicing.agent.actions.ActionPublisher;
import poc.demo.servicing.agent.actions.ActionPublisher.ActionEvent;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Supervisor-approved discretionary waivers (FR-8.2, FR-9.3).
 *
 * <p>The admin API does not write the system of record. It validates the request with reads, then publishes a
 * {@code waiver} action; the downstream system of record applies it (invoice WAIVED, waiver count +1, audit row
 * with the supervisor's JWT subject) and the change reaches Redis through CDC. The response is {@code ACCEPTED}
 * with the action id. The action id is derived from the invoice, so repeating the same override applies it once.
 */
@Service
public class AdminOverrideService {

    private static final Logger log = LoggerFactory.getLogger(AdminOverrideService.class);

    private final JdbcTemplate jdbc;
    private final ActionPublisher actions;

    public AdminOverrideService(JdbcTemplate jdbc, ActionPublisher actions) {
        this.jdbc = jdbc;
        this.actions = actions;
    }

    public OverrideResult applyWaiverOverride(String supervisorSub, String customerId, String invoiceId,
                                              BigDecimal amount, String justification) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM customers WHERE customer_id = ?", Integer.class, customerId);
        if (exists == null || exists == 0) {
            throw new NotFoundException("Customer not found: " + customerId);
        }
        if (invoiceId != null && !invoiceId.isBlank()) {
            Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM billing_ledger WHERE invoice_id = ? AND customer_id = ?",
                    Integer.class, invoiceId, customerId);
            if (found == null || found == 0) {
                throw new NotFoundException("Invoice " + invoiceId + " not found for customer " + customerId);
            }
        }
        ActionEvent event = actions.publish("waiver", customerId, invoiceId, amount, "SUPERVISOR_OVERRIDE", supervisorSub,
                invoiceId == null || invoiceId.isBlank() ? justification : invoiceId, justification);
        log.info("Waiver override accepted: customer={} invoice={} amount={} by={} action={}",
                customerId, invoiceId, amount, supervisorSub, event.actionId());
        return new OverrideResult(event.actionId(), customerId, invoiceId, amount, supervisorSub, Instant.now(), "ACCEPTED");
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }

    /** {@code status=ACCEPTED}: the action is durable on Kafka; the system of record applies it asynchronously. */
    public record OverrideResult(String actionId, String customerId, String invoiceId, BigDecimal amount,
                                 String supervisorSub, Instant timestamp, String status) {}
}
