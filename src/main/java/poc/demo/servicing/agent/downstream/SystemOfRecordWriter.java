package poc.demo.servicing.agent.downstream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import poc.demo.servicing.agent.actions.ActionPublisher.ActionEvent;
import poc.demo.servicing.agent.admin.AuditService;

/**
 * Stand-in for the downstream system of record (billing, case management) that owns the warehouse (FR-9.2).
 * It consumes {@code agent.actions.*} and is the only component that writes source data. In production this
 * is a separate service with its own credentials; it lives in this deployable only so the POC runs locally.
 *
 * <p>Each action is applied once: the action id is inserted into {@code applied_actions} in the same transaction
 * as the write, so a redelivered or duplicate event is skipped. The change then reaches Redis through CDC.
 */
@Component
public class SystemOfRecordWriter {

    private static final Logger log = LoggerFactory.getLogger(SystemOfRecordWriter.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private volatile boolean schemaReady;

    public SystemOfRecordWriter(JdbcTemplate jdbc, TransactionTemplate tx, AuditService audit, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.audit = audit;
        this.mapper = mapper;
    }

    @KafkaListener(topics = {"${agent.actions.topic-prefix:agent.actions.}waiver",
            "${agent.actions.topic-prefix:agent.actions.}escalation",
            "${agent.actions.topic-prefix:agent.actions.}ticket-note",
            "${agent.actions.topic-prefix:agent.actions.}sla-credit"},
            groupId = "system-of-record")
    public void onAction(@Payload String json, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) throws Exception {
        apply(mapper.readValue(json, ActionEvent.class), topic);
    }

    void apply(ActionEvent a, String topic) {
        ensureSchema();
        String outcome = tx.execute(status -> {
            int fresh = jdbc.update("INSERT INTO applied_actions (action_id, action_type, customer_id) VALUES (?, ?, ?) "
                    + "ON CONFLICT (action_id) DO NOTHING", a.actionId(), a.type(), a.customerId());
            if (fresh == 0) {
                return "DUPLICATE_SKIPPED";
            }
            String justification = "action=" + a.actionId() + "; " + (a.note() == null ? "" : a.note());
            switch (a.type()) {
                case "waiver" -> {
                    if (a.invoiceId() != null) {
                        jdbc.update("UPDATE billing_ledger SET status = 'WAIVED' WHERE invoice_id = ? AND customer_id = ?",
                                a.invoiceId(), a.customerId());
                    }
                    jdbc.update("UPDATE customers SET waivers_used_12m = waivers_used_12m + 1, updated_at = NOW() "
                            + "WHERE customer_id = ?", a.customerId());
                    String action = a.actor() != null && a.actor().startsWith("agent:")
                            ? "WAIVER_APPROVED_AUTONOMOUS" : "WAIVER_OVERRIDE";
                    audit.append(action, a.actor(), a.customerId(), a.invoiceId(), a.amount(), justification);
                }
                case "escalation" -> audit.append("ESCALATION_HANDOFF", a.actor(), a.customerId(), null, null,
                        justification + " reason=" + a.reasonCode());
                default -> audit.append(a.type().toUpperCase().replace('-', '_'), a.actor(), a.customerId(),
                        a.invoiceId(), a.amount(), justification);
            }
            return "APPLIED";
        });
        log.info("System of record: {} {} type={} customer={} invoice={} from {}",
                outcome, a.actionId(), a.type(), a.customerId(), a.invoiceId(), topic);
    }

    private void ensureSchema() {
        if (schemaReady) {
            return;
        }
        audit.ensureSchema();
        jdbc.execute("CREATE TABLE IF NOT EXISTS applied_actions (action_id VARCHAR(64) PRIMARY KEY, "
                + "action_type VARCHAR(32) NOT NULL, customer_id VARCHAR(32) NOT NULL, applied_at TIMESTAMPTZ DEFAULT NOW())");
        schemaReady = true;
    }
}
