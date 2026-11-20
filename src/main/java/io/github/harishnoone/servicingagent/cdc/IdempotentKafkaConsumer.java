package io.github.harishnoone.servicingagent.cdc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.harishnoone.servicingagent.model.CustomerProfile;
import io.github.harishnoone.servicingagent.model.CustomerProfile.LedgerEntry;
import io.github.harishnoone.servicingagent.model.CustomerProfile.TicketSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Idempotent CDC consumer that materialises Debezium change events into the Redis L1
 * profile cache (FR-1.1, FR-1.2, FR-1.3).
 *
 * <p><b>Deduplication:</b> {@code SET processed:event:{event_id} 1 NX EX 86400}, where
 * {@code event_id = table:lsn:primaryKey} taken from the Debezium envelope. Because the id
 * comes from the payload rather than the Kafka offset, the same change is recognised on
 * retry topics and after a DLT replay. If processing fails the key is removed again, so a
 * retry or replay is not mistaken for a duplicate.
 *
 * <p><b>LSN ordering:</b> each source row has its own guard key
 * {@code lsn:{table}:{pk}}. An event is applied only when its LSN is strictly greater than
 * the stored one. Guards are per row (not per profile) because the three tables are
 * consumed independently and may arrive out of LSN order; a profile-wide guard would
 * wrongly drop an older billing event that arrives after a newer customer event.
 * {@link CustomerProfile#lastAppliedLsn()} holds the highest LSN applied to the profile.
 *
 * <p>Routing is by the envelope's {@code source.table}, so records redelivered from
 * {@code -retry-N} topics are handled identically to the original topic.
 */
@Component
public class IdempotentKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(IdempotentKafkaConsumer.class);

    private static final int MAX_RECENT_CHARGES = 10;
    private static final String LSN_GUARD_PREFIX = "lsn:";
    private static final int LOCK_STRIPES = 64;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

    @Value("${agent.redis.profile-key-prefix:customer:profile:}")
    private String profileKeyPrefix;

    @Value("${agent.redis.dedup-key-prefix:processed:event:}")
    private String dedupKeyPrefix;

    @Value("${agent.redis.dedup-ttl-seconds:86400}")
    private long dedupTtlSeconds;

    public IdempotentKafkaConsumer(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    @KafkaListener(
            topics = {
                "${agent.kafka.topics.customers}",
                "${agent.kafka.topics.billing}",
                "${agent.kafka.topics.tickets}"
            },
            groupId = "${spring.kafka.consumer.group-id:servicing-agent-group}")
    public void onCdcEvent(
            @Payload String rawPayload,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset) {
        handle(rawPayload, topic, offset);
    }

    /** Core handler, package-visible so tests can drive it without a broker. */
    void handle(String rawPayload, String topic, long offset) {
        String dedupKey = null;
        try {
            JsonNode envelope = parse(rawPayload);
            JsonNode after = envelope.path("after");
            if (after.isMissingNode() || after.isNull()) {
                log.debug("No 'after' image (delete/tombstone) on topic={} offset={}; skipping", topic, offset);
                return;
            }

            String table = envelope.path("source").path("table").asText("");
            if (table.isBlank()) {
                table = tableFromTopic(topic);
            }
            long lsn = envelope.path("source").path("lsn").asLong(0L);
            String pk = primaryKey(table, after);
            String eventId = lsn > 0 && !table.isBlank() && !pk.isBlank()
                    ? table + ":" + lsn + ":" + pk
                    : topic + ":" + offset;

            dedupKey = dedupKeyPrefix + eventId;
            Boolean isNew = redis.opsForValue().setIfAbsent(dedupKey, "1", Duration.ofSeconds(dedupTtlSeconds));
            if (Boolean.FALSE.equals(isNew)) {
                log.info("Duplicate event skipped eventId={}", eventId);
                dedupKey = null;
                return;
            }

            String customerId = after.path("customer_id").asText("");
            if (customerId.isBlank()) {
                log.warn("Event {} has no customer_id; skipping", eventId);
                return;
            }

            ReentrantLock lock = locks[Math.floorMod(customerId.hashCode(), LOCK_STRIPES)];
            lock.lock();
            try {
                switch (table) {
                    case "customers"       -> applyCustomer(after, customerId, lsn);
                    case "billing_ledger"  -> applyBilling(after, customerId, lsn);
                    case "support_tickets" -> applyTicket(after, customerId, lsn);
                    default -> log.warn("Unmapped table '{}' for eventId={}", table, eventId);
                }
            } finally {
                lock.unlock();
            }
            dedupKey = null; // success: keep the key
        } catch (RuntimeException ex) {
            releaseDedup(dedupKey);
            throw ex;
        } catch (Exception ex) {
            releaseDedup(dedupKey);
            throw new IllegalStateException("CDC processing failure topic=" + topic + " offset=" + offset, ex);
        }
    }

    private void releaseDedup(String dedupKey) {
        if (dedupKey != null) {
            redis.delete(dedupKey);
        }
    }

    // -------------------------------------------------------------------------
    // Envelope parsing
    // -------------------------------------------------------------------------

    /** Parses the record, unwrapping the {@code {schema, payload}} wrapper when present. */
    private JsonNode parse(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            if (root == null || root.isMissingNode()) {
                throw new IllegalArgumentException("Empty CDC payload");
            }
            JsonNode payload = root.path("payload");
            return payload.isObject() && payload.has("source") ? payload : root;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unparseable CDC payload: " + e.getMessage(), e);
        }
    }

    private String tableFromTopic(String topic) {
        if (topic.contains("customers")) return "customers";
        if (topic.contains("billing_ledger")) return "billing_ledger";
        if (topic.contains("support_tickets")) return "support_tickets";
        return "";
    }

    private String primaryKey(String table, JsonNode after) {
        return switch (table) {
            case "customers"       -> after.path("customer_id").asText("");
            case "billing_ledger"  -> after.path("invoice_id").asText("");
            case "support_tickets" -> after.path("ticket_id").asText("");
            default -> "";
        };
    }

    // -------------------------------------------------------------------------
    // Row handlers
    // -------------------------------------------------------------------------

    private void applyCustomer(JsonNode after, String customerId, long lsn) throws Exception {
        if (isStale("customers", customerId, lsn)) return;
        String key = profileKeyPrefix + customerId;
        CustomerProfile existing = loadProfile(key);

        CustomerProfile updated = new CustomerProfile(
                customerId,
                after.path("full_name").asText(existing != null ? existing.fullName() : ""),
                after.path("account_tier").asText(existing != null ? existing.accountTier() : "Standard"),
                after.path("tenure_months").asInt(existing != null ? existing.tenureMonths() : 0),
                decimal(after.path("mrr"), existing != null ? existing.mrr() : BigDecimal.ZERO),
                after.path("waivers_used_12m").asInt(existing != null ? existing.waiversUsed12m() : 0),
                Math.max(lsn, existing != null ? existing.lastAppliedLsn() : 0L),
                existing != null ? existing.recentCharges() : new ArrayList<>(),
                existing != null ? existing.activeTickets() : new ArrayList<>());

        saveProfile(key, updated);
        markApplied("customers", customerId, lsn);
    }

    private void applyBilling(JsonNode after, String customerId, long lsn) throws Exception {
        String invoiceId = after.path("invoice_id").asText("");
        if (invoiceId.isBlank() || isStale("billing_ledger", invoiceId, lsn)) return;

        String key = profileKeyPrefix + customerId;
        CustomerProfile existing = loadOrStub(key, customerId);

        LedgerEntry entry = new LedgerEntry(
                invoiceId,
                after.path("charge_type").asText(""),
                decimal(after.path("amount"), BigDecimal.ZERO),
                after.path("status").asText(""),
                after.path("description").asText(""));

        List<LedgerEntry> charges = new ArrayList<>(existing.recentCharges());
        charges.removeIf(e -> e.invoiceId().equals(invoiceId));
        charges.add(0, entry);
        if (charges.size() > MAX_RECENT_CHARGES) {
            charges = new ArrayList<>(charges.subList(0, MAX_RECENT_CHARGES));
        }

        saveProfile(key, new CustomerProfile(existing.customerId(), existing.fullName(),
                existing.accountTier(), existing.tenureMonths(), existing.mrr(),
                existing.waiversUsed12m(), Math.max(lsn, existing.lastAppliedLsn()),
                charges, existing.activeTickets()));
        markApplied("billing_ledger", invoiceId, lsn);
    }

    private void applyTicket(JsonNode after, String customerId, long lsn) throws Exception {
        String ticketId = after.path("ticket_id").asText("");
        if (ticketId.isBlank() || isStale("support_tickets", ticketId, lsn)) return;

        String key = profileKeyPrefix + customerId;
        CustomerProfile existing = loadOrStub(key, customerId);

        TicketSummary ticket = new TicketSummary(
                ticketId,
                after.path("category").asText(""),
                after.path("subject").asText(""),
                after.path("status").asText(""),
                after.path("sla_deadline").asText(""));

        List<TicketSummary> tickets = new ArrayList<>(existing.activeTickets());
        tickets.removeIf(t -> t.ticketId().equals(ticketId));
        tickets.add(0, ticket);

        saveProfile(key, new CustomerProfile(existing.customerId(), existing.fullName(),
                existing.accountTier(), existing.tenureMonths(), existing.mrr(),
                existing.waiversUsed12m(), Math.max(lsn, existing.lastAppliedLsn()),
                existing.recentCharges(), tickets));
        markApplied("support_tickets", ticketId, lsn);
    }

    // -------------------------------------------------------------------------
    // LSN guard + Redis helpers
    // -------------------------------------------------------------------------

    /** True when the stored LSN for this row is greater than or equal to the incoming one. */
    private boolean isStale(String table, String pk, long lsn) {
        String stored = redis.opsForValue().get(LSN_GUARD_PREFIX + table + ":" + pk);
        if (stored != null && lsn <= Long.parseLong(stored)) {
            log.warn("Stale event skipped: {}:{} incoming lsn={} stored lsn={}", table, pk, lsn, stored);
            return true;
        }
        return false;
    }

    private void markApplied(String table, String pk, long lsn) {
        redis.opsForValue().set(LSN_GUARD_PREFIX + table + ":" + pk, Long.toString(lsn));
    }

    private BigDecimal decimal(JsonNode node, BigDecimal fallback) {
        if (node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
            return fallback;
        }
        return new BigDecimal(node.asText());
    }

    private CustomerProfile loadProfile(String key) throws Exception {
        String json = redis.opsForValue().get(key);
        return json == null || json.isBlank() ? null : objectMapper.readValue(json, CustomerProfile.class);
    }

    private CustomerProfile loadOrStub(String key, String customerId) throws Exception {
        CustomerProfile existing = loadProfile(key);
        if (existing != null) {
            return existing;
        }
        log.warn("No profile yet for {}; creating stub until the customers event arrives", customerId);
        return new CustomerProfile(customerId, "", "Standard", 0, BigDecimal.ZERO, 0, 0L,
                new ArrayList<>(), new ArrayList<>());
    }

    private void saveProfile(String key, CustomerProfile profile) throws Exception {
        redis.opsForValue().set(key, objectMapper.writeValueAsString(profile));
    }
}
