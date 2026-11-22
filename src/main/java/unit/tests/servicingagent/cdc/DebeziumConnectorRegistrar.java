package unit.tests.servicingagent.cdc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.InputStream;

/**
 * Registers the Debezium Postgres connector on startup when {@code DEBEZIUM_CONNECT_URL} is set
 * (infrastructure spec, startup step 2). Uses {@code PUT /connectors/{name}/config}, which creates
 * or updates the connector, so restarts are safe. Runs on a virtual thread and retries while
 * Kafka Connect is still booting; failures are logged and never block application startup.
 */
@Component
public class DebeziumConnectorRegistrar {

    private static final Logger log = LoggerFactory.getLogger(DebeziumConnectorRegistrar.class);
    private static final int ATTEMPTS = 30;
    private static final long WAIT_MS = 5_000;

    private final ObjectMapper objectMapper;
    private final String connectUrl;

    public DebeziumConnectorRegistrar(ObjectMapper objectMapper,
                                      @Value("${agent.debezium.connect-url:}") String connectUrl) {
        this.objectMapper = objectMapper;
        this.connectUrl = connectUrl;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerOnStartup() {
        if (connectUrl == null || connectUrl.isBlank()) {
            log.info("agent.debezium.connect-url not set; skipping connector auto-registration");
            return;
        }
        Thread.ofVirtual().name("debezium-registrar").start(this::registerWithRetry);
    }

    void registerWithRetry() {
        JsonNode definition;
        try (InputStream in = new ClassPathResource("debezium/register-postgres-cdc.json").getInputStream()) {
            definition = objectMapper.readTree(in);
        } catch (Exception e) {
            log.error("Cannot read Debezium connector definition", e);
            return;
        }
        String name = definition.path("name").asText();
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(3_000);
        rf.setReadTimeout(10_000);
        RestClient client = RestClient.builder().baseUrl(connectUrl).requestFactory(rf).build();

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                client.put().uri("/connectors/{name}/config", name)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(definition.path("config"))
                        .retrieve().toBodilessEntity();
                log.info("Debezium connector '{}' registered at {}", name, connectUrl);
                return;
            } catch (Exception e) {
                log.info("Debezium not ready (attempt {}/{}): {}", attempt, ATTEMPTS, e.getMessage());
                try {
                    Thread.sleep(WAIT_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.error("Gave up registering Debezium connector '{}'; register it manually", name);
    }
}
