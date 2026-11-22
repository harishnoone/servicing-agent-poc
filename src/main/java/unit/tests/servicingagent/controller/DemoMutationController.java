package unit.tests.servicingagent.controller;

import unit.tests.servicingagent.model.CustomerProfile.TicketSummary;
import unit.tests.servicingagent.admin.AdminOverrideService;
import unit.tests.servicingagent.tools.TicketMatchTools;
import unit.tests.servicingagent.tools.TicketMatchTools.TicketMatch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.TimeUnit;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Live warehouse mutations for the panel demo (FR-1.4). Every write goes to Postgres only;
 * Redis changes arrive through Debezium, Kafka and the CDC consumer.
 */
@RestController
@RequestMapping("/api/demo")
@Tag(name = "Demo")
@Validated
public class DemoMutationController {

    private static final String ID = "^[A-Za-z0-9_\\-]{1,32}$";
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DemoMutationController.class);

    public record MutationRequest(
            @NotBlank @Pattern(regexp = ID) String customerId,
            @NotBlank @Pattern(regexp = "^(SUBSCRIPTION|LATE_FEE|OVERAGE)$",
                    message = "must be SUBSCRIPTION, LATE_FEE or OVERAGE") String chargeType,
            @NotNull @Positive @DecimalMax("100000.00") BigDecimal amount,
            @Size(max = 500) String description
    ) {}

    public record CustomerUpdateRequest(
            @Pattern(regexp = "^(Standard|Gold|Enterprise)$", message = "must be Standard, Gold or Enterprise") String accountTier,
            @Min(0) @Max(1200) Integer tenureMonths,
            @Min(0) @Max(100) Integer waiversUsed12m
    ) {}

    public record CustomerCreateRequest(
            @NotBlank @Pattern(regexp = ID) String customerId,
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Pattern(regexp = "^(Standard|Gold|Enterprise)$", message = "must be Standard, Gold or Enterprise") String accountTier,
            @NotNull @Min(0) @Max(1200) Integer tenureMonths,
            @NotNull @PositiveOrZero @DecimalMax("1000000.00") BigDecimal mrr,
            @Min(0) @Max(100) Integer waiversUsed12m
    ) {}

    /** @param slaHoursFromNow negative = SLA already breached (e.g. -4), positive = deadline in the future */
    public record TicketCreateRequest(
            @NotBlank @Pattern(regexp = ID) String customerId,
            @NotBlank @Size(max = 40) String category,
            @NotBlank @Size(max = 500) String subject,
            @NotBlank @Pattern(regexp = "^(OPEN|PENDING_ENGINEERING|RESOLVED)$", message = "must be OPEN, PENDING_ENGINEERING or RESOLVED") String status,
            @NotNull @Min(-720) @Max(720) Integer slaHoursFromNow
    ) {}

    public record PoisonPillRequest(
            @NotBlank @Pattern(regexp = "^(customers|billing_ledger|support_tickets)$", message = "must be customers, billing_ledger or support_tickets") String table,
            @Size(max = 500) String payload
    ) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StringRedisTemplate redis;
    private final String profileKeyPrefix;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final String topicPrefix;

    public DemoMutationController(JdbcTemplate jdbc, TransactionTemplate tx, StringRedisTemplate redis,
                                  @Value("${agent.redis.profile-key-prefix}") String profileKeyPrefix,
                                  KafkaTemplate<String, String> kafka, ObjectMapper objectMapper,
                                  @Value("${agent.kafka.topic-prefix:db.public.}") String topicPrefix) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.redis = redis;
        this.profileKeyPrefix = profileKeyPrefix;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.topicPrefix = topicPrefix;
    }

    @PostMapping("/mutate")
    @Operation(summary = "Insert a billing ledger row (live CDC trigger)",
            description = "Inserts an UNPAID charge for the customer and touches customers.updated_at, so Debezium "
                    + "publishes CDC events that the consumer applies to Redis. Use for demo step 1.")
    public ResponseEntity<Map<String, Object>> mutate(@RequestBody @Valid MutationRequest request) {
        String invoiceId = "INV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        tx.executeWithoutResult(s -> {
            requireCustomer(request.customerId());
            jdbc.update("INSERT INTO billing_ledger (invoice_id, customer_id, charge_type, amount, status, description) "
                            + "VALUES (?, ?, ?, ?, 'UNPAID', ?)",
                    invoiceId, request.customerId(), request.chargeType(), request.amount(), request.description());
            jdbc.update("UPDATE customers SET updated_at = NOW() WHERE customer_id = ?", request.customerId());
        });

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("invoiceId", invoiceId);
        body.put("customerId", request.customerId());
        body.put("chargeType", request.chargeType());
        body.put("amount", request.amount());
        body.put("status", "UNPAID");
        body.put("message", "Row inserted. Debezium will publish it to db.public.billing_ledger.");
        return ResponseEntity.ok(body);
    }

    @PostMapping("/customers/{customerId}")
    @Operation(summary = "Update a customer row (live CDC trigger)",
            description = "Updates tier, tenure or waiver count in the warehouse; the change reaches Redis via CDC.")
    public ResponseEntity<Map<String, Object>> updateCustomer(
            @PathVariable @Pattern(regexp = ID) String customerId,
            @RequestBody @Valid CustomerUpdateRequest request) {
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (request.accountTier() != null)    { sets.add("account_tier = ?");     args.add(request.accountTier()); }
        if (request.tenureMonths() != null)   { sets.add("tenure_months = ?");    args.add(request.tenureMonths()); }
        if (request.waiversUsed12m() != null) { sets.add("waivers_used_12m = ?"); args.add(request.waiversUsed12m()); }
        if (sets.isEmpty()) {
            throw new IllegalArgumentException("Provide at least one of accountTier, tenureMonths, waiversUsed12m");
        }
        args.add(customerId);
        tx.executeWithoutResult(s -> {
            requireCustomer(customerId);
            jdbc.update("UPDATE customers SET " + String.join(", ", sets) + ", updated_at = NOW() WHERE customer_id = ?",
                    args.toArray());
        });
        return ResponseEntity.ok(Map.of("customerId", customerId, "updated", request,
                "message", "Row updated. Debezium will publish it to db.public.customers."));
    }

    @DeleteMapping("/cache/{customerId}")
    @Operation(summary = "Evict a cached profile (simulate a dormant account)",
            description = "Deletes customer:profile:{id} from Redis so the next agent turn exercises the "
                    + "asymmetric cache-miss fallback to the warehouse and the async re-hydration.")
    public ResponseEntity<Map<String, Object>> evict(@PathVariable @Pattern(regexp = ID) String customerId) {
        Boolean removed = redis.delete(profileKeyPrefix + customerId);
        return ResponseEntity.ok(Map.of("customerId", customerId, "evicted", Boolean.TRUE.equals(removed)));
    }

    @PostMapping("/customers")
    @Operation(summary = "Create a customer (live CDC trigger)",
            description = "Inserts a new customer row; Debezium publishes it and the consumer creates the Redis profile.")
    public ResponseEntity<Map<String, Object>> createCustomer(@RequestBody @Valid CustomerCreateRequest request) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM customers WHERE customer_id = ?", Integer.class,
                request.customerId());
        if (exists != null && exists > 0) {
            throw new ConflictException("Customer already exists: " + request.customerId());
        }
        jdbc.update("INSERT INTO customers (customer_id, full_name, account_tier, tenure_months, mrr, waivers_used_12m) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                request.customerId(), request.fullName(), request.accountTier(), request.tenureMonths(), request.mrr(),
                request.waiversUsed12m() == null ? 0 : request.waiversUsed12m());
        return ResponseEntity.ok(Map.of("customerId", request.customerId(), "status", "CREATED",
                "message", "Row inserted. Debezium will publish it to db.public.customers."));
    }

    @PostMapping("/tickets")
    @Operation(summary = "Create a support ticket (live CDC trigger), unless one is already open for the same problem",
            description = "Inserts a ticket with an SLA deadline relative to now. Use a negative slaHoursFromNow "
                    + "(e.g. -4) with status PENDING_ENGINEERING to create an SLA-breach escalation scenario. "
                    + "FR-6.4: if the customer already has an open ticket for the same problem (shared error code, "
                    + "same subject, or same category and topic), no row is inserted and the existing ticket is "
                    + "returned with duplicate=true.")
    public ResponseEntity<Map<String, Object>> createTicket(@RequestBody @Valid TicketCreateRequest request) {
        String newId = "TKT-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TicketMatch existing = tx.execute(s -> {
            requireCustomer(request.customerId());
            // Serialise ticket creation per customer so two concurrent requests cannot both pass the check.
            jdbc.queryForObject("SELECT customer_id FROM customers WHERE customer_id = ? FOR UPDATE",
                    String.class, request.customerId());
            if (!"RESOLVED".equals(request.status())) {
                // The warehouse is the source of truth here; Redis may not have the newest ticket yet.
                List<TicketSummary> open = jdbc.query(
                        "SELECT ticket_id, category, subject, status, sla_deadline FROM support_tickets "
                                + "WHERE customer_id = ? AND status NOT IN ('RESOLVED', 'CLOSED') "
                                + "ORDER BY last_updated DESC",
                        (rs, i) -> new TicketSummary(rs.getString("ticket_id"), rs.getString("category"),
                                rs.getString("subject"), rs.getString("status"), rs.getString("sla_deadline")),
                        request.customerId());
                TicketMatch dup = TicketMatchTools.duplicateOf(open, request.category(), request.subject());
                if (dup.matched()) {
                    return dup;
                }
            }
            jdbc.update("INSERT INTO support_tickets (ticket_id, customer_id, category, subject, status, sla_deadline) "
                            + "VALUES (?, ?, ?, ?, ?, NOW() + make_interval(hours => ?))",
                    newId, request.customerId(), request.category(), request.subject(), request.status(),
                    request.slaHoursFromNow());
            return null;
        });

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", request.customerId());
        if (existing != null) {
            log.info("Duplicate ticket prevented: existing={} reason={}", existing.ticketId(), existing.matchReason());
            body.put("ticketId", existing.ticketId());
            body.put("duplicate", true);
            body.put("matchReason", existing.matchReason());
            body.put("existingStatus", existing.status());
            body.put("existingSubject", existing.subject());
            body.put("message", "Open ticket " + existing.ticketId() + " already covers this problem ("
                    + existing.matchReason() + "); no new ticket was created.");
            return ResponseEntity.ok(body);
        }
        body.put("ticketId", newId);
        body.put("duplicate", false);
        body.put("slaBreached", request.slaHoursFromNow() < 0);
        body.put("message", "Row inserted. Debezium will publish it to db.public.support_tickets.");
        return ResponseEntity.ok(body);
    }

    @GetMapping("/profile/{customerId}")
    @Operation(summary = "Read the cached Redis profile",
            description = "Returns exactly what the agent sees in the L1 cache, or 404 when the profile is not cached "
                    + "(e.g. after eviction or before CDC delivers it).")
    public ResponseEntity<JsonNode> profile(@PathVariable @Pattern(regexp = ID) String customerId) throws Exception {
        String json = redis.opsForValue().get(profileKeyPrefix + customerId);
        if (json == null) {
            throw new AdminOverrideService.NotFoundException(
                    "No cached profile for " + customerId);
        }
        return ResponseEntity.ok(objectMapper.readTree(json));
    }

    @PostMapping("/poison-pill")
    @Operation(summary = "Publish an unparseable CDC message",
            description = "Sends a malformed record to db.public.{table} to demonstrate retries (1s, 2s, 4s) and "
                    + "dead-letter routing. Check GET /api/admin/kafka/dlt about 8 seconds later.")
    public ResponseEntity<Map<String, Object>> poisonPill(@RequestBody @Valid PoisonPillRequest request) throws Exception {
        String topic = topicPrefix + request.table();
        String payload = request.payload() == null || request.payload().isBlank()
                ? "this-is-not-json" : request.payload();
        kafka.send(topic, "poison-pill", payload).get(10, TimeUnit.SECONDS);
        return ResponseEntity.ok(Map.of("topic", topic, "payload", payload,
                "message", "Published. Expect it on " + topic + ".DLT after 3 retries (~8s)."));
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) { super(message); }
    }

    private void requireCustomer(String customerId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM customers WHERE customer_id = ?", Integer.class, customerId);
        if (n == null || n == 0) {
            throw new AdminOverrideService.NotFoundException("Customer not found: " + customerId);
        }
    }
}
