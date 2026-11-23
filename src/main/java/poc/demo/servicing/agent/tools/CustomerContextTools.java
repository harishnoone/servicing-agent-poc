package poc.demo.servicing.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import poc.demo.servicing.agent.cache.WarehouseFallbackRepository;
import poc.demo.servicing.agent.cdc.IdempotentKafkaConsumer;
import poc.demo.servicing.agent.model.CustomerProfile;
import poc.demo.servicing.agent.telemetry.KpiMetricsService;
import poc.demo.servicing.agent.telemetry.TraceCollector;
import poc.demo.servicing.agent.telemetry.TurnState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Customer state for the agent (FR-3.2, FR-3.4).
 *
 * <p>Reads the Redis L1 cache first. On a cache miss (e.g. a dormant account whose profile was
 * never hydrated), it performs the asymmetric fallback: a bounded indexed point lookup in the
 * warehouse, then re-hydrates Redis on a virtual thread so the turn does not wait for the write.
 * Re-hydration uses {@code SET NX}, so a profile written by the CDC consumer in the meantime is
 * never overwritten by the older snapshot.
 */
@Component
public class CustomerContextTools {

    private static final Logger log = LoggerFactory.getLogger(CustomerContextTools.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String profileKeyPrefix;
    private final WarehouseFallbackRepository warehouse;
    private final boolean fallbackEnabled;
    private final KpiMetricsService kpi;
    private final TraceCollector traceCollector;
    private final TurnState turnState;

    public CustomerContextTools(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${agent.redis.profile-key-prefix}") String profileKeyPrefix,
            WarehouseFallbackRepository warehouse,
            @Value("${agent.cache-miss.fallback-enabled:true}") boolean fallbackEnabled,
            KpiMetricsService kpi,
            TraceCollector traceCollector,
            TurnState turnState) {
        this.redisTemplate    = redisTemplate;
        this.objectMapper     = objectMapper;
        this.profileKeyPrefix = profileKeyPrefix;
        this.warehouse        = warehouse;
        this.fallbackEnabled  = fallbackEnabled;
        this.kpi              = kpi;
        this.traceCollector   = traceCollector;
        this.turnState        = turnState;
    }

    @Tool(description = "Retrieve the live customer profile including account tier, tenure, MRR, waivers "
            + "used, recent charges, and active support tickets. Call this first for any account inquiry.")
    public CustomerProfile getCustomerState(
            @ToolParam(description = "Customer account ID, e.g. cust_123") String customerId) {
        long start = System.currentTimeMillis();
        String source = "REDIS_HIT";
        try {
            CustomerProfile cached = turnState.cachedProfile(customerId);
            if (cached != null) {
                source = turnState.profileSource(customerId);
                return cached;
            }
            CustomerProfile fromRedis = readRedis(customerId);
            if (fromRedis != null) {
                turnState.cacheProfile(customerId, fromRedis, source);
                return fromRedis;
            }
            source = "CACHE_MISS -> WAREHOUSE_FALLBACK";
            return fallback(customerId);
        } finally {
            turnState.recordTool("getCustomerState");
            traceCollector.record("TOOL_CALL: getCustomerState (" + source + ")",
                    System.currentTimeMillis() - start);
        }
    }

    /**
     * Profile lookup shared by other tools and the controller, untraced. Uses the same
     * Redis-then-warehouse path and the per-turn memo, so one turn triggers at most one fallback.
     *
     * @throws IllegalStateException when the customer exists in neither Redis nor the warehouse
     */
    public CustomerProfile loadProfile(String customerId) {
        CustomerProfile cached = turnState.cachedProfile(customerId);
        if (cached != null) {
            return cached;
        }
        CustomerProfile fromRedis = readRedis(customerId);
        if (fromRedis != null && IdempotentKafkaConsumer.isStub(fromRedis)) {
            // FR-1.5: a stub (billing/ticket rows but no customer row yet) must never drive a decision.
            log.warn("Cached profile for {} is incomplete (no customer row yet); reading the warehouse", customerId);
            fromRedis = null;
        }
        if (fromRedis != null) {
            turnState.cacheProfile(customerId, fromRedis, "REDIS_HIT");
            return fromRedis;
        }
        return fallback(customerId);
    }

    private CustomerProfile readRedis(String customerId) {
        String json = redisTemplate.opsForValue().get(profileKeyPrefix + customerId);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, CustomerProfile.class);
        } catch (Exception e) {
            log.warn("Unreadable cached profile for {}; treating as cache miss: {}", customerId, e.getMessage());
            return null;
        }
    }

    private CustomerProfile fallback(String customerId) {
        if (!fallbackEnabled) {
            throw new IllegalStateException("Customer profile not found for: " + customerId);
        }
        kpi.recordCacheMiss();
        CustomerProfile profile = warehouse.findProfile(customerId)
                .orElseThrow(() -> new IllegalStateException("Customer not found: " + customerId));
        turnState.cacheProfile(customerId, profile, "CACHE_MISS -> WAREHOUSE_FALLBACK");
        rehydrateAsync(customerId, profile);
        return profile;
    }

    private void rehydrateAsync(String customerId, CustomerProfile profile) {
        Thread.ofVirtual().name("rehydrate-" + customerId).start(() -> {
            try {
                Boolean written = redisTemplate.opsForValue()
                        .setIfAbsent(profileKeyPrefix + customerId, objectMapper.writeValueAsString(profile));
                log.info("Cache re-hydration for {}: {}", customerId,
                        Boolean.TRUE.equals(written) ? "written" : "skipped (CDC already wrote a profile)");
            } catch (Exception e) {
                log.warn("Cache re-hydration failed for {}: {}", customerId, e.getMessage());
            }
        });
    }
}
