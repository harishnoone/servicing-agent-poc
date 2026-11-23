package poc.demo.servicing.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link GeminiThoughtSignatureInterceptor} on the RestClient Spring AI uses, only when the
 * OpenAI base URL points at Google's OpenAI-compatible Gemini endpoint.
 */
@Configuration
@ConditionalOnExpression("'${spring.ai.openai.base-url:}'.contains('generativelanguage.googleapis.com')")
public class GeminiCompatConfig {

    private static final Logger log = LoggerFactory.getLogger(GeminiCompatConfig.class);

    @Bean
    RestClientCustomizer geminiThoughtSignatureCustomizer(ObjectMapper mapper) {
        log.info("Gemini endpoint detected: tool-call thought signatures will be round-tripped");
        GeminiThoughtSignatureInterceptor interceptor = new GeminiThoughtSignatureInterceptor(mapper);
        return builder -> builder.requestInterceptor(interceptor);
    }
}