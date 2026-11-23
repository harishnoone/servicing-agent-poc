package poc.demo.servicing.agent.config;

import poc.demo.servicing.agent.prompt.StaticPromptPrefix;
import poc.demo.servicing.agent.tools.CustomerContextTools;
import poc.demo.servicing.agent.tools.DeterministicRulesTools;
import poc.demo.servicing.agent.tools.EscalationTools;
import poc.demo.servicing.agent.tools.KnowledgeRetrievalTools;
import poc.demo.servicing.agent.tools.ReportingTools;
import poc.demo.servicing.agent.tools.TicketMatchTools;
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
