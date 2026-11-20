package io.github.harishnoone.servicingagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.harishnoone.servicingagent.telemetry.TraceCollector;
import io.github.harishnoone.servicingagent.telemetry.TurnState;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/** Adds round-trip timing and logging to the RestClient Spring AI uses for the model provider. */
@Configuration
public class LlmHttpConfig {

    @Bean
    @Order(0)
    RestClientCustomizer llmCallLoggingCustomizer(ObjectMapper mapper, ObjectProvider<TraceCollector> trace,
                                                  ObjectProvider<TurnState> turnState) {
        LlmCallLoggingInterceptor interceptor = new LlmCallLoggingInterceptor(mapper, trace, turnState);
        return builder -> builder.requestInterceptor(interceptor);
    }
}