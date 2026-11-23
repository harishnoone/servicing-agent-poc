package poc.demo.servicing.agent.admin;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Append-only audit trail in the warehouse database. A trigger (see {@code audit-schema.sql})
 * rejects UPDATE and DELETE, so entries are immutable once written (FR-8.2).
 */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the table and immutability trigger when missing (the seed script predates them). */
    public void ensureSchema() {
        if (schemaReady.get()) {
            return;
        }
        try {
            jdbc.execute(new ClassPathResource("audit-schema.sql").getContentAsString(StandardCharsets.UTF_8));
            schemaReady.set(true);
        } catch (Exception e) {
            throw new IllegalStateException("Could not prepare audit_log table", e);
        }
    }

    /**
     * Appends an entry. Joins the caller's transaction when one is active.
     *
     * @return generated audit id, or {@code null} if the driver did not return it
     */
    public Long append(String action, String actor, String customerId, String invoiceId,
                       BigDecimal amount, String justification) {
        ensureSchema();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO audit_log (action, supervisor_sub, customer_id, invoice_id, amount, justification) "
                            + "VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, action);
            ps.setString(2, actor);
            ps.setString(3, customerId);
            ps.setString(4, invoiceId);
            ps.setBigDecimal(5, amount);
            ps.setString(6, justification);
            return ps;
        }, keys);
        Object id = keys.getKeys() == null ? null : keys.getKeys().get("audit_id");
        return id instanceof Number n ? n.longValue() : null;
    }
}
