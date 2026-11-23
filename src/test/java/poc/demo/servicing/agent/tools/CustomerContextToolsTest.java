package poc.demo.servicing.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import poc.demo.servicing.agent.cache.WarehouseFallbackRepository;
import poc.demo.servicing.agent.model.CustomerProfile;
import poc.demo.servicing.agent.support.FakeRedis;
import poc.demo.servicing.agent.telemetry.KpiMetricsService;
import poc.demo.servicing.agent.telemetry.TraceCollector;
import poc.demo.servicing.agent.telemetry.TurnState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/** FR-3.4 asymmetric cache-miss fallback. */
class CustomerContextToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final FakeRedis redis = new FakeRedis();
    private final WarehouseFallbackRepository warehouse = mock(WarehouseFallbackRepository.class);
    private final KpiMetricsService kpi = new KpiMetricsService();
    private final TraceCollector trace = new TraceCollector();
    private final CustomerContextTools tools = new CustomerContextTools(
            redis.template, mapper, "customer:profile:", warehouse, true, kpi, trace, new TurnState());

    private static CustomerProfile dormant() {
        return new CustomerProfile("cust_900", "Dormant", "Gold", 40, BigDecimal.ONE, 0, 0L, List.of(), List.of());
    }

    @Test
    void cacheHitNeverTouchesWarehouse() throws Exception {
        redis.store.put("customer:profile:cust_900", mapper.writeValueAsString(dormant()));
        assertThat(tools.getCustomerState("cust_900").accountTier()).isEqualTo("Gold");
        verifyNoInteractions(warehouse);
        assertThat(trace.getSteps().get(0).event()).contains("REDIS_HIT");
    }

    @Test
    void cacheMissFallsBackOnceAndRehydratesAsync() {
        when(warehouse.findProfile("cust_900")).thenReturn(Optional.of(dormant()));

        assertThat(tools.getCustomerState("cust_900").tenureMonths()).isEqualTo(40);
        tools.loadProfile("cust_900"); // same turn: memo, no second lookup

        verify(warehouse, times(1)).findProfile("cust_900");
        assertThat(trace.getSteps().get(0).event()).contains("CACHE_MISS -> WAREHOUSE_FALLBACK");
        assertThat(kpi.getReport().cacheMissFallbacks()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(2)).until(() -> redis.store.containsKey("customer:profile:cust_900"));
    }

    @Test
    void rehydrationNeverOverwritesProfileWrittenByCdc() throws Exception {
        when(warehouse.findProfile("cust_900")).thenAnswer(i -> {
            // CDC lands a newer profile while the fallback query is running
            redis.store.put("customer:profile:cust_900", "{\"customerId\":\"cust_900\",\"accountTier\":\"Enterprise\"}");
            return Optional.of(dormant());
        });
        tools.getCustomerState("cust_900");
        Thread.sleep(300);
        assertThat(redis.store.get("customer:profile:cust_900")).contains("Enterprise");
    }

    @Test
    void unknownCustomerFails() {
        when(warehouse.findProfile("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> tools.getCustomerState("nope")).hasMessageContaining("Customer not found");
    }
}
