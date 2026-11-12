package info.saagar.stockagent.config;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import dev.langchain4j.service.tool.ToolProvider;
import info.saagar.stockagent.agent.QuoteAgent;
import info.saagar.stockagent.logging.ToolExecutionLogger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/// Manual LangChain4j wiring for the Quote agent (no Spring Boot starter).
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class QuoteAgentConfig {

  /// Key of the Quote agent's settings in `application.yml`; also logged as its agent name.
  public static final String QUOTE_AGENT_NAME = "quote";

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
        .timeout(quoteSettings.timeout())
        .build();
  }

  /// Builds the Quote agent's tool provider: the market-data tools on its allowlist, and no others.
  ///
  /// @param marketDataMcpClient client for `market-data-mcp`
  /// @param agentProperties per-agent settings holding the allowlist
  /// @return the tool provider
  @Bean
  public ToolProvider quoteToolProvider(
      McpClient marketDataMcpClient, AgentProperties agentProperties) {
    return McpToolProvider.builder()
        .mcpClients(marketDataMcpClient)
        .filterToolNames(agentProperties.forAgent(QUOTE_AGENT_NAME).allowedTools())
        .failIfOneServerFails(true)
        .build();
  }

  /// Builds the Quote agent around the chat model and its MCP tools.
  ///
  /// A failed tool call (an MCP result with `isError: true`) is sent to the model as the tool's
  /// result text, which is the AiServices default. Setting the handler explicitly only drops the
  /// default's unstructured warning; `ToolExecutionLogger` already logs the failure.
  ///
  /// @param quoteChatModel chat model used by the agent
  /// @param quoteToolProvider the MCP tools on the agent's allowlist
  /// @param agentProperties per-agent settings
  /// @param toolExecutionLogger logs each tool call the agent makes
  /// @return the Quote agent
  @Bean
  public QuoteAgent quoteAgent(
      ChatModel quoteChatModel,
      ToolProvider quoteToolProvider,
      AgentProperties agentProperties,
      ToolExecutionLogger toolExecutionLogger) {
    return AiServices.builder(QuoteAgent.class)
        .chatModel(quoteChatModel)
        .toolProvider(quoteToolProvider)
        .toolExecutionErrorHandler(ToolExecutionErrorHandler.sendExceptionMessageToLlm())
        .afterToolExecution(toolExecutionLogger)
        .maxSequentialToolsInvocations(agentProperties.forAgent(QUOTE_AGENT_NAME).maxSteps())
        .build();
  }
}
