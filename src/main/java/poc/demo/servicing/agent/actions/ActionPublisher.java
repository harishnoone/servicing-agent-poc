package poc.demo.servicing.agent.actions;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import poc.demo.servicing.agent.telemetry.TraceCollector;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Action facade (FR-9.1): the app never writes the system of record. Every resolution that changes something
 * (waiver, escalation, ticket follow-up, SLA-credit review) is published as an {@link ActionEvent} to
 * {@code agent.actions.<type>}, keyed by customer id, and a downstream system applies it. The source data stays
 * read-only for the agent and the admin API. Events carry ids and reason codes only, no PII.
 */
@Component
public class ActionPublisher {

    private static final Logger log = LoggerFactory.getLogger(ActionPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final ObjectProvider<TraceCollector> trace;
    private final String topicPrefix;

    public ActionPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper, ObjectProvider<TraceCollector> trace,
                           @Value("${agent.actions.topic-prefix:agent.actions.}") String topicPrefix) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.trace = trace;
        this.topicPrefix = topicPrefix;
    }

    /**
     * Publishes the action and waits for the broker acknowledgement, so a returned id means the action is durable.
     * The action id is derived from its content (type, customer, invoice, idempotency key), so a retried turn or a
     * double-clicked admin request produces the same id and the downstream writer applies it once.
     */
    public ActionEvent publish(String type, String customerId, String invoiceId, BigDecimal amount, String reasonCode,
                               String actor, String idempotencyKey, String note) {
        String actionId = type + "-" + UUID.nameUUIDFromBytes(String.join("|", type, customerId,
                String.valueOf(invoiceId), String.valueOf(idempotencyKey)).getBytes(StandardCharsets.UTF_8))
                .toString().substring(0, 12);
        ActionEvent event = new ActionEvent(actionId, type, customerId, invoiceId, amount, reasonCode, actor, note,
                Instant.now().toString());
        String topic = topicPrefix + type;
        String payload;
        try {
            payload = mapper.writeValueAsString(event);
            kafka.send(topic, customerId, payload).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not publish action " + actionId + " to " + topic, e);
        }
        // One line per produced action, with the exact payload sent (ids and reason codes only, no PII).
        log.info("Action produced: topic={} key={} actionId={} type={} reason={} payload={}",
                topic, customerId, actionId, type, reasonCode, payload);
        if (RequestContextHolder.getRequestAttributes() != null) {
            trace.getObject().record("ACTION_PUBLISHED: " + type + " " + actionId + " -> " + topic);
        }
        return event;
    }

    /**
     * @param actionId   content-derived id, used by the downstream writer for idempotency
     * @param type       {@code waiver}, {@code escalation}, {@code ticket-note} or {@code sla-credit}
     * @param reasonCode rule outcome that justified the action, e.g. {@code APPROVED}, {@code SLA_BREACH}
     * @param actor      {@code agent:tier1} or the supervisor's JWT subject
     * @param note       short non-PII context (justification, ticket id)
     */
    public record ActionEvent(String actionId, String type, String customerId, String invoiceId, BigDecimal amount,
                              String reasonCode, String actor, String note, String createdAt) {}
}
