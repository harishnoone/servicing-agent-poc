package unit.tests.servicingagent.cdc;

import unit.tests.servicingagent.dlt.DltService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import unit.tests.servicingagent.cache.WarehouseFallbackRepository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FR-1.3 against a real (embedded) broker: a poison pill is retried through the
 * -retry-0/1/2 topics with 1s/2s/4s backoff, then lands on {@code <topic>.DLT} with
 * exception headers, while a healthy record on the same topic is still processed.
 * Also checks FR-8.3 replay: the DLT record is republished and depth drops to 0.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "agent.llm.mode=replay",
        "agent.debezium.connect-url="
})
@EmbeddedKafka(partitions = 1, topics = {"db.public.customers", "db.public.billing_ledger", "db.public.support_tickets"})
class DeadLetterRoutingTest {

    @Autowired EmbeddedKafkaBroker broker;
    @Autowired KafkaTemplate<String, String> template;
    @Autowired DltService dltService;
    @MockitoBean StringRedisTemplate redis;
    @MockitoBean
    WarehouseFallbackRepository warehouse; // no DB here: empty -> event applied as-is

    private static final String GOOD = """
            {"after":{"customer_id":"cust_123","full_name":"Elena","account_tier":"Gold","tenure_months":36,
             "mrr":"499.00","waivers_used_12m":0},"source":{"table":"customers","lsn":10}}""";

    @Test
    @SuppressWarnings("unchecked")
    void poisonPillGoesToDltAfterBackoffAndCanBeReplayed() throws Exception {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        List<String> written = java.util.Collections.synchronizedList(new ArrayList<>());
        org.mockito.Mockito.doAnswer(i -> { written.add(i.getArgument(0)); return null; })
                .when(ops).set(anyString(), anyString());

        long start = System.currentTimeMillis();
        template.send("db.public.customers", "k1", "this is not json").get();
        template.send("db.public.customers", "k2", GOOD).get();

        Map<String, Object> props = KafkaTestUtils.consumerProps("dlt-assert", "false", broker);
        props.put("auto.offset.reset", "earliest");
        try (Consumer<String, String> c = new DefaultKafkaConsumerFactory<>(props,
                new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            c.subscribe(List.of("db.public.customers.DLT"));
            ConsumerRecord<String, String> dead =
                    KafkaTestUtils.getSingleRecord(c, "db.public.customers.DLT", Duration.ofSeconds(30));
            long elapsed = System.currentTimeMillis() - start;

            assertThat(dead.value()).isEqualTo("this is not json");
            assertThat(new String(dead.headers().lastHeader(KafkaHeaders.ORIGINAL_TOPIC).value(),
                    StandardCharsets.UTF_8)).startsWith("db.public.customers");
            assertThat(new String(dead.headers().lastHeader(KafkaHeaders.EXCEPTION_MESSAGE).value(),
                    StandardCharsets.UTF_8)).contains("Unparseable CDC payload");
            assertThat(elapsed).as("1s + 2s + 4s backoff").isGreaterThanOrEqualTo(6_500);
        }

        // The healthy record behind the poison pill was not blocked
        assertThat(written).contains("customer:profile:cust_123");

        List<DltService.DltMessage> pending = dltService.peek("db.public.customers.DLT", 5);
        assertThat(pending).singleElement().satisfies(m -> {
            assertThat(m.originalTopic()).isEqualTo("db.public.customers");
            assertThat(m.exceptionClass()).isEqualTo("java.lang.IllegalArgumentException");
        });
        assertThat(dltService.depth("db.public.customers.DLT")).isEqualTo(1);
        DltService.ReplayResult replay = dltService.replay("db.public.customers.DLT", 10);
        assertThat(replay.replayed()).isEqualTo(1);
        assertThat(replay.republishedTo()).isEqualTo("db.public.customers");
        assertThat(dltService.depth("db.public.customers.DLT")).isZero();
    }
}
