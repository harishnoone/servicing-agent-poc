package poc.demo.servicing.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import poc.demo.servicing.agent.llm.LlmCircuitBreaker;
import poc.demo.servicing.agent.telemetry.TraceCollector;
import poc.demo.servicing.agent.telemetry.TurnState;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/** Adds round-trip timing and logging to the RestClient Spring AI uses for the model provider. */
@Configuration
public class LlmHttpConfig {

    @Bean
    @Order(0)
    RestClientCustomizer llmCallLoggingCustomizer(ObjectMapper mapper, ObjectProvider<TraceCollector> trace,
                                                  ObjectProvider<TurnState> turnState, LlmCircuitBreaker breaker) {
        LlmCallLoggingInterceptor interceptor = new LlmCallLoggingInterceptor(mapper, trace, turnState, breaker);
        return builder -> builder.requestInterceptor(interceptor);
    }

    /**
     * Connect and read timeouts for every model call. Single-pass has its own synthesis cap, but agent planner
     * mode makes several calls in a row and, without a read timeout, one slow provider response would block the
     * turn indefinitely. A timed-out call fails the turn, which then falls back to the deterministic answer.
     */
    @Bean
    @Order(1)
    RestClientCustomizer llmTimeoutCustomizer(
            @Value("${agent.llm.http-connect-timeout-seconds:10}") int connectSeconds,
            @Value("${agent.llm.http-read-timeout-seconds:30}") int readSeconds) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectSeconds)).build();
        return builder -> {
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
            factory.setReadTimeout(Duration.ofSeconds(readSeconds));
            builder.requestFactory(factory);
        };
    }
}