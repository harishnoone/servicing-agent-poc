package io.github.harishnoone.servicingagent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.retrytopic.RetryTopicConfiguration;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationBuilder;

import java.util.List;

/**
 * Non-blocking retry and dead-letter routing for the CDC consumer (FR-1.3).
 *
 * <p>Uses Spring Kafka's retry-topic mechanism rather than in-place blocking retries:
 * a failed record is republished to {@code <topic>-retry-0/1/2}, which are consumed
 * after 1s, 2s and 4s respectively, so the main topic partition keeps flowing. After
 * the third retry fails, the record goes to {@code <topic>.DLT} with the original
 * headers and exception headers attached.
 */
@Configuration
public class KafkaResiliencyConfig {

    public static final String DLT_SUFFIX = ".DLT";

    static final long INITIAL_INTERVAL_MS = 1_000L;
    static final double MULTIPLIER        = 2.0;
    static final long MAX_INTERVAL_MS     = 4_000L;
    /** 1 original delivery + 3 retries. */
    static final int MAX_ATTEMPTS         = 4;

    @Bean
    @ConditionalOnProperty(name = "agent.kafka.retry-topics.enabled", matchIfMissing = true)
    public RetryTopicConfiguration cdcRetryTopicConfiguration(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${agent.kafka.topics.customers}") String customers,
            @Value("${agent.kafka.topics.billing}") String billing,
            @Value("${agent.kafka.topics.tickets}") String tickets) {
        return RetryTopicConfigurationBuilder.newInstance()
                .includeTopics(List.of(customers, billing, tickets))
                .exponentialBackoff(INITIAL_INTERVAL_MS, MULTIPLIER, MAX_INTERVAL_MS)
                .maxAttempts(MAX_ATTEMPTS)
                .retryTopicSuffix("-retry")
                .dltSuffix(DLT_SUFFIX)
                .autoCreateTopics(true, 1, (short) 1)
                .create(kafkaTemplate);
    }
}
