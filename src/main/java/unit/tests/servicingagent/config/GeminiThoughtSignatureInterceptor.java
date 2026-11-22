package unit.tests.servicingagent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gemini 3.x (OpenAI-compatible endpoint) returns a {@code thought_signature} on every tool call
 * ({@code tool_calls[].extra_content.google.thought_signature}) and rejects the next request with
 * HTTP 400 if the assistant tool call is sent back without it. Spring AI 1.0's OpenAI client drops
 * unknown fields, so this interceptor remembers signatures by tool-call id from each response and
 * re-attaches them to the matching tool calls in the next request. Requests to other providers
 * pass through untouched (the interceptor is only registered for a Gemini base URL).
 */
public class GeminiThoughtSignatureInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(GeminiThoughtSignatureInterceptor.class);
    private static final int MAX_ENTRIES = 5_000;

    private final ObjectMapper mapper;
    private final Map<String, String> signatures = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, false) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > MAX_ENTRIES; }
            });

    public GeminiThoughtSignatureInterceptor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        byte[] outgoing = body;
        if (isChatCompletion(request) && body.length > 0) {
            outgoing = attachSignatures(body);
        }
        ClientHttpResponse response = execution.execute(request, outgoing);
        if (!isChatCompletion(request) || !response.getStatusCode().is2xxSuccessful()) {
            return response;
        }
        byte[] responseBody = response.getBody().readAllBytes();
        rememberSignatures(responseBody);
        return new BufferedResponse(response, responseBody);
    }

    private static boolean isChatCompletion(HttpRequest request) {
        return request.getURI().getPath().endsWith("/chat/completions");
    }

    byte[] attachSignatures(byte[] body) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode messages = root.path("messages");
            if (!messages.isArray()) return body;
            int attached = 0;
            for (JsonNode m : messages) {
                JsonNode calls = m.path("tool_calls");
                if (!calls.isArray()) continue;
                for (JsonNode c : calls) {
                    if (!(c instanceof ObjectNode call) || call.has("extra_content")) continue;
                    String sig = signatures.get(call.path("id").asText(""));
                    if (sig == null) continue;
                    call.putObject("extra_content").putObject("google").put("thought_signature", sig);
                    attached++;
                }
            }
            return attached == 0 ? body : mapper.writeValueAsBytes(root);
        } catch (IOException e) {
            log.debug("Could not parse chat request for thought signatures; sending unchanged", e);
            return body;
        }
    }

    void rememberSignatures(byte[] responseBody) {
        try {
            JsonNode choices = mapper.readTree(responseBody).path("choices");
            if (!(choices instanceof ArrayNode)) return;
            for (JsonNode choice : choices) {
                for (JsonNode c : choice.path("message").path("tool_calls")) {
                    String id = c.path("id").asText("");
                    String sig = c.path("extra_content").path("google").path("thought_signature").asText("");
                    if (!id.isEmpty() && !sig.isEmpty()) signatures.put(id, sig);
                }
            }
        } catch (IOException e) {
            log.debug("Could not parse chat response for thought signatures", e);
        }
    }

    int size() { return signatures.size(); }

    /** Response whose body was read once and is replayed to Spring AI. */
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