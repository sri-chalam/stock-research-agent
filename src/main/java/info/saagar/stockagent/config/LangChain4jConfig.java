package info.saagar.stockagent.config;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import info.saagar.stockagent.agent.QuoteAgent;
import info.saagar.stockagent.tool.QuoteTools;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/// Manual LangChain4j wiring (no Spring Boot starter).
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class LangChain4jConfig {

  static final String QUOTE_AGENT_NAME = "quote";

  /// Builds the Claude chat model for the Quote agent from its `application.yml` settings.
  ///
  /// @param agentProperties per-agent settings
  /// @param anthropicApiKey API key from the `ANTHROPIC_API_KEY` environment variable
  /// @return the chat model
  @Bean
  public ChatModel quoteChatModel(
      AgentProperties agentProperties, @Value("${ANTHROPIC_API_KEY}") String anthropicApiKey) {
    AgentProperties.AgentSettings quoteSettings = agentProperties.forAgent(QUOTE_AGENT_NAME);
    return AnthropicChatModel.builder()
        .apiKey(anthropicApiKey)
        .modelName(quoteSettings.model())
        .maxTokens(quoteSettings.maxTokens())
        .temperature(quoteSettings.temperature())
        .timeout(quoteSettings.timeout())
        .build();
  }

  /// Builds the Quote agent around the chat model and the quote tools.
  ///
  /// @param quoteChatModel chat model used by the agent
  /// @param quoteTools tools the agent may call
  /// @param agentProperties per-agent settings
  /// @return the Quote agent
  @Bean
  public QuoteAgent quoteAgent(
      ChatModel quoteChatModel, QuoteTools quoteTools, AgentProperties agentProperties) {
    return AiServices.builder(QuoteAgent.class)
        .chatModel(quoteChatModel)
        .tools(quoteTools)
        .maxSequentialToolsInvocations(agentProperties.forAgent(QUOTE_AGENT_NAME).maxSteps())
        .build();
  }
}
