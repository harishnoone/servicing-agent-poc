package unit.tests.servicingagent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import unit.tests.servicingagent.telemetry.TraceCollector;
import unit.tests.servicingagent.telemetry.TurnState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.context.request.RequestContextHolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Times every HTTP call to the model provider. A turn with tools makes one call per planning step
 * (tool selection, then the answer), so these round trips are where turn latency goes. Each call
 * is logged at INFO with status, duration and the provider's raw token usage, and added to the
 * turn's reasoning trace as {@code LLM_ROUND_TRIP #n (HTTP 200)} with its latency.
 */
public class LlmCallLoggingInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LlmCallLoggingInterceptor.class);

    private final ObjectMapper mapper;
    private final ObjectProvider<TraceCollector> trace;
    private final ObjectProvider<TurnState> turnState;

    public LlmCallLoggingInterceptor(ObjectMapper mapper, ObjectProvider<TraceCollector> trace,
                                     ObjectProvider<TurnState> turnState) {
        this.mapper = mapper;
        this.trace = trace;
        this.turnState = turnState;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (!request.getURI().getPath().endsWith("/chat/completions")) {
            return execution.execute(request, body);
        }
        long start = System.nanoTime();
        ClientHttpResponse response;
        try {
            response = execution.execute(request, body);
        } catch (IOException e) {
            finish(start, "IO_ERROR " + e.getClass().getSimpleName(), null);
            throw e;
        }
        int status = response.getStatusCode().value();
        byte[] responseBody = response.getBody().readAllBytes();
        finish(start, "HTTP " + status, status == 200 ? usage(responseBody) : errorSummary(responseBody));
        return new BufferedResponse(response, responseBody);
    }

    private void finish(long startNanos, String outcome, String detail) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        boolean inRequest = RequestContextHolder.getRequestAttributes() != null;
        int n = inRequest ? turnState.getObject().recordLlmRoundTrip(ms) : 0;
        if (inRequest) {
            trace.getObject().record("LLM_ROUND_TRIP #" + n + " (" + outcome + ")", ms);
        }
        if (outcome.equals("HTTP 200")) {
            log.info("LLM round trip #{} {} in {} ms, usage={}", n, outcome, ms, detail);
        } else {
            log.warn("LLM round trip #{} {} in {} ms: {}", n, outcome, ms, detail);
        }
    }

    private String usage(byte[] responseBody) {
        try {
            JsonNode usage = mapper.readTree(responseBody).path("usage");
            return usage.isMissingNode() ? "none" : usage.toString();
        } catch (IOException e) {
            return "unparsed";
        }
    }

    private String errorSummary(byte[] responseBody) {
        String s = new String(responseBody, java.nio.charset.StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    private static final class BufferedResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final byte[] body;

        BufferedResponse(ClientHttpResponse delegate, byte[] body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override public HttpStatusCode getStatusCode() throws IOException { return delegate.getStatusCode(); }
        @Override public String getStatusText() throws IOException { return delegate.getStatusText(); }
        @Override public HttpHeaders getHeaders() { return delegate.getHeaders(); }
        @Override public InputStream getBody() { return new ByteArrayInputStream(body); }
        @Override public void close() { delegate.close(); }
    }
}