package poc.demo.servicing.agent.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Operational customer state hydrated into Redis L1 cache by IdempotentKafkaConsumer.
 * Read by getCustomerState() @Tool in sub-millisecond lookups — never queried
 * from the warehouse synchronously during an agent turn (FR-3.2).
 */
public record CustomerProfile(
        String customerId,
        String fullName,
        String accountTier,
        int tenureMonths,
        BigDecimal mrr,
        int waiversUsed12m,
        long lastAppliedLsn,
        List<LedgerEntry> recentCharges,
        List<TicketSummary> activeTickets
) {
    public record LedgerEntry(
            String invoiceId,
            String chargeType,
            BigDecimal amount,
            String status,
            String description
    ) {}

    public record TicketSummary(
            String ticketId,
            String category,
            String subject,
            String status,
            String slaDeadline
    ) {}
}
