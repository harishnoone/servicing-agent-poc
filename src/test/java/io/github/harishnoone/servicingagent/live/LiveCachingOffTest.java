package io.github.harishnoone.servicingagent.live;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".{10,}")
@SpringBootTest(properties = {"agent.llm.mode=llm", "agent.prompt-caching.enabled=false",
        "agent.kafka.retry-topics.enabled=false", "spring.kafka.listener.auto-startup=false", "agent.debezium.connect-url="})
@AutoConfigureMockMvc
class LiveCachingOffTest extends LiveCachingBase {
    @Override String label() { return "prompt-caching=false"; }
}