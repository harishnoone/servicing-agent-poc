package poc.demo.servicing.agent.dlt;

import poc.demo.servicing.agent.config.KafkaResiliencyConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Inspection and replay of dead-letter topics (FR-8.3) and the DLT depth KPI (FR-7.2).
 *
 * <p>"Depth" is the number of DLT records not yet replayed, measured against the committed
 * offsets of a dedicated replay consumer group. Only the three configured CDC dead-letter
 * topics can be touched; an arbitrary topic name is rejected.
 */
@Service
public class DltService {

    private static final Logger log = LoggerFactory.getLogger(DltService.class);
    static final String REPLAY_GROUP = "servicing-agent-dlt-replay";
    private static final Duration POLL = Duration.ofSeconds(2);

    private final ConsumerFactory<String, String> consumerFactory;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Set<String> dltTopics;

    public DltService(ConsumerFactory<String, String> consumerFactory,
                      KafkaTemplate<String, String> kafkaTemplate,
                      @Value("${agent.kafka.topics.customers}") String customers,
                      @Value("${agent.kafka.topics.billing}") String billing,
                      @Value("${agent.kafka.topics.tickets}") String tickets) {
        this.consumerFactory = consumerFactory;
        this.kafkaTemplate = kafkaTemplate;
        Set<String> topics = new LinkedHashSet<>();
        for (String t : List.of(customers, billing, tickets)) {
            topics.add(t + KafkaResiliencyConfig.DLT_SUFFIX);
        }
        this.dltTopics = Collections.unmodifiableSet(topics);
    }

    public Set<String> dltTopics() {
        return dltTopics;
    }

    /** Total unreplayed records across all DLTs; 0 when Kafka or the topics are unavailable. */
    public long totalDepth() {
        long total = 0;
        for (String topic : dltTopics) {
            total += depth(topic);
        }
        return total;
    }

    public long depth(String dltTopic) {
        try (Consumer<String, String> consumer = newConsumer()) {
            List<TopicPartition> partitions = partitions(consumer, dltTopic);
            if (partitions.isEmpty()) {
                return 0;
            }
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
            Map<TopicPartition, Long> begins = consumer.beginningOffsets(partitions);
            Map<TopicPartition, OffsetAndMetadata> committed = consumer.committed(new HashSet<>(partitions));
            long depth = 0;
            for (TopicPartition tp : partitions) {
                OffsetAndMetadata c = committed.get(tp);
                long start = c != null ? c.offset() : begins.getOrDefault(tp, 0L);
                depth += Math.max(0, ends.getOrDefault(tp, 0L) - start);
            }
            return depth;
        } catch (Exception e) {
            log.debug("Could not compute DLT depth for {}: {}", dltTopic, e.getMessage());
            return 0;
        }
    }

    /** Returns up to {@code max} pending records with their failure headers, without consuming them. */
    public List<DltMessage> peek(String dltTopic, int max) {
        requireKnown(dltTopic);
        List<DltMessage> out = new ArrayList<>();
        try (Consumer<String, String> consumer = newConsumer()) {
            List<TopicPartition> partitions = partitions(consumer, dltTopic);
            if (partitions.isEmpty()) {
                return out;
            }
            consumer.assign(partitions);
            seekToPending(consumer, partitions);
            ConsumerRecords<String, String> records = consumer.poll(POLL);
            for (ConsumerRecord<String, String> r : records) {
                if (out.size() >= max) break;
                out.add(toMessage(r));
            }
        }
        return out;
    }

    /**
     * Republishes up to {@code max} pending DLT records to their original topic and commits
     * the replay group's offsets so the same records are not replayed twice.
     */
    public ReplayResult replay(String dltTopic, int max) {
        requireKnown(dltTopic);
        String original = dltTopic.substring(0, dltTopic.length() - KafkaResiliencyConfig.DLT_SUFFIX.length());
        int replayed = 0;
        try (Consumer<String, String> consumer = newConsumer()) {
            List<TopicPartition> partitions = partitions(consumer, dltTopic);
            if (partitions.isEmpty()) {
                return new ReplayResult(dltTopic, original, 0);
            }
            consumer.assign(partitions);
            seekToPending(consumer, partitions);
            ConsumerRecords<String, String> records = consumer.poll(POLL);
            Map<TopicPartition, OffsetAndMetadata> toCommit = new HashMap<>();
            for (ConsumerRecord<String, String> r : records) {
                if (replayed >= max) break;
                try {
                    kafkaTemplate.send(original, r.key(), r.value()).get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("Replay to " + original + " failed at offset " + r.offset(), e);
                }
                replayed++;
                toCommit.put(new TopicPartition(r.topic(), r.partition()), new OffsetAndMetadata(r.offset() + 1));
            }
            if (!toCommit.isEmpty()) {
                consumer.commitSync(toCommit);
            }
        }
        log.info("Replayed {} record(s) from {} to {}", replayed, dltTopic, original);
        return new ReplayResult(dltTopic, original, replayed);
    }

    // -------------------------------------------------------------------------

    private void requireKnown(String dltTopic) {
        if (!dltTopics.contains(dltTopic)) {
            throw new IllegalArgumentException("Unknown dead-letter topic '" + dltTopic + "'. Allowed: " + dltTopics);
        }
    }

    private Consumer<String, String> newConsumer() {
        Properties overrides = new Properties();
        overrides.put("enable.auto.commit", "false");
        overrides.put("auto.offset.reset", "earliest");
        overrides.put("default.api.timeout.ms", "5000");
        overrides.put("request.timeout.ms", "4000");
        overrides.put("max.poll.records", "100");
        return consumerFactory.createConsumer(REPLAY_GROUP, "dlt-admin", null, overrides);
    }

    private List<TopicPartition> partitions(Consumer<String, String> consumer, String topic) {
        List<PartitionInfo> infos = consumer.partitionsFor(topic, Duration.ofSeconds(4));
        if (infos == null) {
            return List.of();
        }
        return infos.stream().map(i -> new TopicPartition(i.topic(), i.partition())).toList();
    }

    private void seekToPending(Consumer<String, String> consumer, List<TopicPartition> partitions) {
        Map<TopicPartition, OffsetAndMetadata> committed = consumer.committed(new HashSet<>(partitions));
        Map<TopicPartition, Long> begins = consumer.beginningOffsets(partitions);
        for (TopicPartition tp : partitions) {
            OffsetAndMetadata c = committed.get(tp);
            consumer.seek(tp, c != null ? c.offset() : begins.getOrDefault(tp, 0L));
        }
    }

    private DltMessage toMessage(ConsumerRecord<String, String> r) {
        return new DltMessage(r.topic(), r.partition(), r.offset(), r.key(), r.value(),
                header(r, KafkaHeaders.ORIGINAL_TOPIC),
                header(r, KafkaHeaders.EXCEPTION_MESSAGE),
                header(r, KafkaHeaders.EXCEPTION_CAUSE_FQCN));
    }

    private String header(ConsumerRecord<?, ?> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    public record DltMessage(String topic, int partition, long offset, String key, String payload,
                             String originalTopic, String exceptionMessage, String exceptionClass) {}

    public record ReplayResult(String dltTopic, String republishedTo, int replayed) {}
}
