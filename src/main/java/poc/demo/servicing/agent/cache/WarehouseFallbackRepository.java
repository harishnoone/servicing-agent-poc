package poc.demo.servicing.agent.cache;

import poc.demo.servicing.agent.model.CustomerProfile;
import poc.demo.servicing.agent.model.CustomerProfile.LedgerEntry;
import poc.demo.servicing.agent.model.CustomerProfile.TicketSummary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Asymmetric cache-miss fallback (design section 6, "dormant accounts or cache misses").
 *
 * <p>Runs three bounded, indexed point lookups by {@code customer_id} against the warehouse:
 * the customer row (primary key), the 10 newest ledger rows and the unresolved tickets (both via
 * the {@code customer_id} indexes in {@code init.sql}). Each statement has a query timeout so a
 * slow warehouse cannot push a chat turn past the latency budget.
 */
@Repository
public class WarehouseFallbackRepository {

    private static final int MAX_RECENT_CHARGES = 10;

    private final JdbcTemplate jdbc;
    private final int queryTimeoutSeconds;

    public WarehouseFallbackRepository(JdbcTemplate jdbc,
                                       @Value("${agent.cache-miss.query-timeout-seconds:1}") int queryTimeoutSeconds) {
        this.jdbc = jdbc;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    /** @return the profile assembled from the warehouse, or empty when the customer does not exist */
    public Optional<CustomerProfile> findProfile(String customerId) {
        JdbcTemplate bounded = new JdbcTemplate(jdbc.getDataSource());
        bounded.setQueryTimeout(queryTimeoutSeconds);
        bounded.setMaxRows(MAX_RECENT_CHARGES);

        List<CustomerProfile> customers = bounded.query(
                "SELECT customer_id, full_name, account_tier, tenure_months, mrr, waivers_used_12m "
                        + "FROM customers WHERE customer_id = ?",
                (rs, i) -> new CustomerProfile(
                        rs.getString("customer_id"), rs.getString("full_name"), rs.getString("account_tier"),
                        rs.getInt("tenure_months"), rs.getBigDecimal("mrr"), rs.getInt("waivers_used_12m"),
                        0L, List.of(), List.of()),
                customerId);
        if (customers.isEmpty()) {
            return Optional.empty();
        }
        CustomerProfile base = customers.get(0);

        List<LedgerEntry> charges = bounded.query(
                "SELECT invoice_id, charge_type, amount, status, description FROM billing_ledger "
                        + "WHERE customer_id = ? ORDER BY created_at DESC LIMIT " + MAX_RECENT_CHARGES,
                (rs, i) -> new LedgerEntry(rs.getString("invoice_id"), rs.getString("charge_type"),
                        rs.getBigDecimal("amount"), rs.getString("status"),
                        rs.getString("description") == null ? "" : rs.getString("description")),
                customerId);

        List<TicketSummary> tickets = bounded.query(
                "SELECT ticket_id, category, subject, status, sla_deadline FROM support_tickets "
                        + "WHERE customer_id = ? AND status NOT IN ('RESOLVED','CLOSED') "
                        + "ORDER BY last_updated DESC LIMIT " + MAX_RECENT_CHARGES,
                (rs, i) -> {
                    OffsetDateTime sla = rs.getObject("sla_deadline", OffsetDateTime.class);
                    return new TicketSummary(rs.getString("ticket_id"), rs.getString("category"),
                            rs.getString("subject"), rs.getString("status"), sla == null ? "" : sla.toString());
                },
                customerId);

        // lastAppliedLsn stays 0: the snapshot has no LSN, so any later CDC event still applies.
        return Optional.of(new CustomerProfile(base.customerId(), base.fullName(), base.accountTier(),
                base.tenureMonths(), base.mrr(), base.waiversUsed12m(), 0L, charges, tickets));
    }
}
