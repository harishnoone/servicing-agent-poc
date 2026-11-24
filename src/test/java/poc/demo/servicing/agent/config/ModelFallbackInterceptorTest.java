package poc.demo.servicing.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import poc.demo.servicing.agent.llm.LlmCircuitBreaker;
import poc.demo.servicing.agent.telemetry.TraceCollector;
import poc.demo.servicing.agent.telemetry.TurnState;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ModelFallbackInterceptorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final URI CHAT = URI.create("https://example.test/v1beta/openai/chat/completions");

    @SuppressWarnings("unchecked")
    private final ObjectProvider<TraceCollector> trace = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<TurnState> turnState = mock(ObjectProvider.class);
    private final MutableClock clock = new MutableClock();

    /** Fake provider: answers with the status configured per model and records which models were called. */
    private static final class FakeProvider implements ClientHttpRequestExecution {
        final Map<String, HttpStatus> statusByModel;
        final List<String> calledModels = new ArrayList<>();
        final List<Long> contentLengths = new ArrayList<>();
        IOException failWith;

        FakeProvider(Map<String, HttpStatus> statusByModel) {
            this.statusByModel = statusByModel;
        }

        @Override
        public ClientHttpResponse execute(HttpRequest request, byte[] body) throws IOException {
            String model = MAPPER.readTree(body).path("model").asText();
            calledModels.add(model);
            contentLengths.add(request.getHeaders().getContentLength());
            if (failWith != null) {
                throw failWith;
            }
            HttpStatus status = statusByModel.getOrDefault(model, HttpStatus.OK);
            String json = status == HttpStatus.OK ? "{\"usage\":{}}" : "{\"error\":{\"status\":\"UNAVAILABLE\"}}";
            return new MockClientHttpResponse(json.getBytes(StandardCharsets.UTF_8), status);
        }
    }

    private ModelFallbackInterceptor interceptor(String... fallbacks) {
        return new ModelFallbackInterceptor(MAPPER, trace, List.of(fallbacks), 60, clock);
    }

    private static MockClientHttpRequest chatRequest(byte[] body) {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, CHAT);
        request.getHeaders().setContentLength(body.length);
        return request;
    }

    private static byte[] body(String model) {
        return ("{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static ClientHttpResponse call(ModelFallbackInterceptor interceptor, FakeProvider provider)
            throws IOException {
        byte[] body = body("primary");
        return interceptor.intercept(chatRequest(body), body, provider);
    }

    @Test
    void switchesToBackupModelOn503AndUpdatesContentLength() throws IOException {
        FakeProvider provider = new FakeProvider(Map.of("primary", HttpStatus.SERVICE_UNAVAILABLE));
        ClientHttpResponse response = call(interceptor("gemini-2.5-flash-backup"), provider);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(provider.calledModels).containsExactly("primary", "gemini-2.5-flash-backup");
        byte[] expected = body("primary");
        assertThat(provider.contentLengths.get(0)).isEqualTo(expected.length);
        assertThat(provider.contentLengths.get(1)).isEqualTo(expected.length + "gemini-2.5-flash-backup".length()
                - "primary".length());
    }

    @Test
    void triesBackupsInOrderAndReturnsLast503WhenAllAreBusy() throws IOException {
        FakeProvider provider = new FakeProvider(Map.of(
                "primary", HttpStatus.SERVICE_UNAVAILABLE,
                "b1", HttpStatus.SERVICE_UNAVAILABLE,
                "b2", HttpStatus.SERVICE_UNAVAILABLE));
        ClientHttpResponse response = call(interceptor("b1", "b2"), provider);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(provider.calledModels).containsExactly("primary", "b1", "b2");
    }

    @Test
    void timeoutDoesNotSwitchModels() {
        FakeProvider provider = new FakeProvider(Map.of());
        provider.failWith = new HttpTimeoutException("request timed out");

        assertThatThrownBy(() -> call(interceptor("backup"), provider)).isInstanceOf(HttpTimeoutException.class);
        assertThat(provider.calledModels).containsExactly("primary");
    }

    @Test
    void otherErrorStatusesDoNotSwitchModels() throws IOException {
        for (HttpStatus status : List.of(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.INTERNAL_SERVER_ERROR,
                HttpStatus.BAD_REQUEST)) {
            FakeProvider provider = new FakeProvider(Map.of("primary", status));
            ClientHttpResponse response = call(interceptor("backup"), provider);
            assertThat(response.getStatusCode()).isEqualTo(status);
            assertThat(provider.calledModels).containsExactly("primary");
        }
    }

    @Test
    void doesNothingWhenNoBackupModelsConfigured() throws IOException {
        FakeProvider provider = new FakeProvider(Map.of("primary", HttpStatus.SERVICE_UNAVAILABLE));
        ModelFallbackInterceptor interceptor = new ModelFallbackInterceptor(MAPPER, trace, List.of(" ", ""), 60, clock);

        assertThat(call(interceptor, provider).getStatusCode().value()).isEqualTo(503);
        assertThat(provider.calledModels).containsExactly("primary");
    }

    @Test
    void staysOnBackupForStickyWindowThenTriesPrimaryAgain() throws IOException {
        ModelFallbackInterceptor interceptor = interceptor("backup");
        FakeProvider busy = new FakeProvider(Map.of("primary", HttpStatus.SERVICE_UNAVAILABLE));
        call(interceptor, busy);

        FakeProvider second = new FakeProvider(Map.of("primary", HttpStatus.SERVICE_UNAVAILABLE));
        clock.advanceSeconds(30);
        call(interceptor, second);
        assertThat(second.calledModels).containsExactly("backup");

        FakeProvider third = new FakeProvider(Map.of());
        clock.advanceSeconds(31);
        call(interceptor, third);
        assertThat(third.calledModels).containsExactly("primary");
    }

    @Test
    void busyStickyBackupFallsBackToPrimary() throws IOException {
        ModelFallbackInterceptor interceptor = interceptor("backup");
        call(interceptor, new FakeProvider(Map.of("primary", HttpStatus.SERVICE_UNAVAILABLE)));

        FakeProvider backupBusy = new FakeProvider(Map.of("backup", HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(call(interceptor, backupBusy).getStatusCode().value()).isEqualTo(200);
        assertThat(backupBusy.calledModels).containsExactly("backup", "primary");

        FakeProvider next = new FakeProvider(Map.of());
        call(interceptor, next);
        assertThat(next.calledModels).containsExactly("primary");
    }

    @Test
    void circuitBreakerSeesOnlyTheFinalOutcome() throws IOException {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(1, 60);
        LlmCallLoggingInterceptor logging = new LlmCallLoggingInterceptor(MAPPER, trace, turnState, breaker);
        ModelFallbackInterceptor fallback = interceptor("backup");
        byte[] body = body("primary");

        // Primary busy, backup answers: no failure recorded, even with a threshold of 1.
        FakeProvider backupAnswers = new FakeProvider(Map.of("primary", HttpStatus.SERVICE_UNAVAILABLE));
        logging.intercept(chatRequest(body), body, (r, b) -> fallback.intercept(r, b, backupAnswers));
        assertThat(breaker.state()).isEqualTo(LlmCircuitBreaker.State.CLOSED);

        // Every model busy: one failure, which opens the circuit at threshold 1.
        FakeProvider allBusy = new FakeProvider(Map.of(
                "primary", HttpStatus.SERVICE_UNAVAILABLE, "backup", HttpStatus.SERVICE_UNAVAILABLE));
        logging.intercept(chatRequest(body), body, (r, b) -> fallback.intercept(r, b, allBusy));
        assertThat(breaker.state()).isEqualTo(LlmCircuitBreaker.State.OPEN);
    }

    /**
     * Regression: a retry from an interceptor skips every interceptor registered after it, so the fallback must
     * be last. Found live: the backup model's response bypassed the thought-signature interceptor and the next
     * planner round trip failed with HTTP 400 "missing a thought_signature".
     */
    @Test
    void backupModelSignaturesSurviveInConfiguredInterceptorOrder() {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(3, 60);
        RestClient.Builder builder = RestClient.builder()
                .requestInterceptor(new LlmCallLoggingInterceptor(MAPPER, trace, turnState, breaker)) // order 0
                .requestInterceptor(new GeminiThoughtSignatureInterceptor(MAPPER))                   // order 2
                .requestInterceptor(interceptor("backup"));                                          // order 3
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String toolCall = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"c1\","
                + "\"type\":\"function\",\"function\":{\"name\":\"getCustomerState\",\"arguments\":\"{}\"},"
                + "\"extra_content\":{\"google\":{\"thought_signature\":\"SIG\"}}}]}}]}";
        server.expect(jsonPath("$.model").value("primary")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(jsonPath("$.model").value("backup")).andRespond(withSuccess(toolCall, MediaType.APPLICATION_JSON));
        server.expect(jsonPath("$.model").value("backup"))
                .andExpect(jsonPath("$.messages[1].tool_calls[0].extra_content.google.thought_signature").value("SIG"))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));
        RestClient client = builder.build();

        client.post().uri(CHAT).contentType(MediaType.APPLICATION_JSON)
                .body("{\"model\":\"primary\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .retrieve().toBodilessEntity();
        client.post().uri(CHAT).contentType(MediaType.APPLICATION_JSON)
                .body("{\"model\":\"primary\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"},"
                        + "{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"getCustomerState\",\"arguments\":\"{}\"}}]},"
                        + "{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"content\":\"{}\"}]}")
                .retrieve().toBodilessEntity();

        server.verify();
        assertThat(breaker.state()).isEqualTo(LlmCircuitBreaker.State.CLOSED);
    }

    @Test
    void ignoresNonChatEndpoints() throws IOException {
        FakeProvider provider = new FakeProvider(Map.of("primary", HttpStatus.SERVICE_UNAVAILABLE));
        byte[] body = body("primary");
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("https://x/embeddings"));

        assertThat(interceptor("backup").intercept(request, body, provider).getStatusCode().value()).isEqualTo(503);
        assertThat(provider.calledModels).containsExactly("primary");
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-24T00:00:00Z");

        void advanceSeconds(long s) {
            now = now.plusSeconds(s);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
