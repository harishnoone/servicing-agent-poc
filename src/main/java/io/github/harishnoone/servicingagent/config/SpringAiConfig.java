package io.github.harishnoone.servicingagent.config;

import io.github.harishnoone.servicingagent.prompt.StaticPromptPrefix;
import io.github.harishnoone.servicingagent.tools.CustomerContextTools;
import io.github.harishnoone.servicingagent.tools.DeterministicRulesTools;
import io.github.harishnoone.servicingagent.tools.EscalationTools;
import io.github.harishnoone.servicingagent.tools.KnowledgeRetrievalTools;
import io.github.harishnoone.servicingagent.tools.ReportingTools;
import io.github.harishnoone.servicingagent.tools.TicketMatchTools;
import org.springframework.ai.chat.client.ChatClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Builds the {@link ChatClient} with the agent's tools (FR-3.1). The system prompt lives in
 * {@code application.yml} under {@code agent.prompts.system} (NFR-3). The system message is the\n * {@link StaticPromptPrefix}: identical on every call so providers can cache it (FR-3.5). Tool\n * registration order is fixed for the same reason (FR-3.6).
 */
@Configuration
public class SpringAiConfig {

    /** Agentic mode: the model plans and calls the tools itself (one provider round trip per step). */
    @Bean
    @Primary
    public ChatClient chatClient(
            ChatClient.Builder builder,
            StaticPromptPrefix staticPromptPrefix,
            CustomerContextTools customerContextTools,
            KnowledgeRetrievalTools knowledgeRetrievalTools,
            DeterministicRulesTools deterministicRulesTools,
            EscalationTools escalationTools,
            TicketMatchTools ticketMatchTools,
            ReportingTools reportingTools) {
        return builder
                .defaultSystem(staticPromptPrefix.text())
                .defaultTools(customerContextTools, knowledgeRetrievalTools, deterministicRulesTools,
                        escalationTools, ticketMatchTools, reportingTools)
                .build();
    }

    /** Single-pass mode: no tools, so a turn costs exactly one provider round trip. */
    @Bean
    public ChatClient synthesisChatClient(ChatClient.Builder builder, StaticPromptPrefix staticPromptPrefix) {
        return builder.defaultSystem(staticPromptPrefix.synthesisText()).build();
    }
}
