package poc.demo.servicing.agent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import poc.demo.servicing.agent.telemetry.TraceCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.web.context.request.RequestContextHolder;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Switches to a backup model when the configured one answers HTTP 503 (provider says the model is busy).
 * Only 503 triggers a switch: timeouts, I/O errors, 429 and other statuses pass through unchanged and are
 * handled by the circuit breaker. The interceptor sits closer to the network than
 * {@link LlmCallLoggingInterceptor}, so the breaker only sees the final outcome: a backup that answers counts
 * as a success, and a failure is recorded only when every model returned 503.
 * <p>
 * After a backup answers, later calls go straight to that backup for {@code stickySeconds}. This saves a
 * wasted call per request and avoids switching models in the middle of an agentic turn. With no backup
 * models configured the interceptor does nothing.
 */
public class ModelFallbackInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ModelFallbackInterceptor.class);

    private final ObjectMapper mapper;
    private final ObjectProvider<TraceCollector> trace;
    private final List<String> fallbackModels;
    private final long stickyMillis;
    private final Clock clock;

    private String stickyModel;
    private long stickyUntil;

    public ModelFallbackInterceptor(ObjectMapper mapper, ObjectProvider<TraceCollector> trace,
                                    List<String> fallbackModels, long stickySeconds, Clock clock) {
        this.mapper = mapper;
        this.trace = trace;
        this.fallbackModels = fallbackModels.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.stickyMillis = stickySeconds * 1000;
        this.clock = clock;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (fallbackModels.isEmpty() || !request.getURI().getPath().endsWith("/chat/completions")) {
            return execution.execute(request, body);
        }
        ObjectNode json;
        try {
            JsonNode parsed = mapper.readTree(body);
            if (!(parsed instanceof ObjectNode node) || !node.hasNonNull("model")) {
                return execution.execute(request, body);
            }
            json = node;
        } catch (IOException e) {
            return execution.execute(request, body);
        }
        String primary = json.get("model").asText();
        List<String> chain = new ArrayList<>();
        chain.add(primary);
        fallbackModels.stream().filter(m -> !m.equals(primary)).forEach(chain::add);

        // Start with the sticky backup if one is active, then the rest of the chain, wrapping round to the
        // configured model, so each model is tried at most once per call.
        String sticky = currentSticky();
        int start = sticky == null ? 0 : Math.max(0, chain.indexOf(sticky));
        List<String> order = new ArrayList<>(chain.subList(start, chain.size()));
        order.addAll(chain.subList(0, start));

        ClientHttpResponse response = null;
        String previous = null;
        for (String model : order) {
            if (response != null) {
                response.close();
                log.warn("LLM model {} returned HTTP 503 (busy); retrying with model {}", previous, model);
                recordTrace("LLM_MODEL_FALLBACK: " + previous + " -> " + model + " (HTTP 503)");
            }
            boolean isPrimary = model.equals(primary);
            byte[] attemptBody = isPrimary ? body : withModel(json, model);
            HttpRequest attempt = isPrimary ? request : withContentLength(request, attemptBody.length);
            response = execution.execute(attempt, attemptBody);
            previous = model;
            if (response.getStatusCode().value() != 503) {
                if (isPrimary) {
                    clearSticky();
                } else if (response.getStatusCode().is2xxSuccessful() && !model.equals(sticky)) {
                    stick(model); // a fresh switch starts the window; staying on the backup does not extend it
                }
                return response;
            }
        }
        clearSticky();
        return response;
    }

    private byte[] withModel(ObjectNode json, String model) throws IOException {
        ObjectNode copy = json.deepCopy();
        copy.put("model", model);
        return mapper.writeValueAsBytes(copy);
    }

    private static HttpRequest withContentLength(HttpRequest request, int length) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(request.getHeaders());
        if (headers.getContentLength() >= 0) {
            headers.setContentLength(length);
        }
        return new HttpRequestWrapper(request) {
            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }

    private synchronized String currentSticky() {
        if (stickyModel != null && clock.millis() >= stickyUntil) {
            log.info("LLM backup model {} window ended; next call tries the configured model", stickyModel);
            stickyModel = null;
        }
        return stickyModel;
    }

    private synchronized void stick(String model) {
        log.info("LLM using backup model {} for {} s", model, stickyMillis / 1000);
        stickyModel = model;
        stickyUntil = clock.millis() + stickyMillis;
    }

    private synchronized void clearSticky() {
        stickyModel = null;
    }

    private void recordTrace(String step) {
        if (RequestContextHolder.getRequestAttributes() != null) {
            trace.getObject().record(step, 0);
        }
    }
}
