package info.saagar.stockagent.config;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import dev.langchain4j.service.tool.ToolProvider;
import info.saagar.stockagent.agent.MarketDataAgent;
import info.saagar.stockagent.logging.ToolExecutionLogger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/// Manual LangChain4j wiring for the Market data agent (no Spring Boot starter).
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class MarketDataAgentConfig {

  /// Key of the Market data agent's settings in `application.yml`; also logged as its agent name.
  public static final String MARKET_DATA_AGENT_NAME = "market-data";

  /// Builds the Claude chat model for the Market data agent from its `application.yml` settings.
  ///
  /// @param agentProperties per-agent settings
  /// @param anthropicApiKey API key from the `ANTHROPIC_API_KEY` environment variable
  /// @return the chat model
  @Bean
  public ChatModel marketDataChatModel(
      AgentProperties agentProperties, @Value("${ANTHROPIC_API_KEY}") String anthropicApiKey) {
    AgentProperties.AgentSettings marketDataSettings =
        agentProperties.forAgent(MARKET_DATA_AGENT_NAME);
    return AnthropicChatModel.builder()
        .apiKey(anthropicApiKey)
        .modelName(marketDataSettings.model())
        .maxTokens(marketDataSettings.maxTokens())
        .timeout(marketDataSettings.timeout())
        .build();
  }

  /// Builds the Market data agent's tool provider: the market-data tools on its allowlist, and no
  /// others.
  ///
  /// @param marketDataMcpClient client for `market-data-mcp`
  /// @param agentProperties per-agent settings holding the allowlist
  /// @return the tool provider
  @Bean
  public ToolProvider marketDataToolProvider(
      McpClient marketDataMcpClient, AgentProperties agentProperties) {
    return McpToolProvider.builder()
        .mcpClients(marketDataMcpClient)
        .filterToolNames(agentProperties.forAgent(MARKET_DATA_AGENT_NAME).allowedTools())
        .failIfOneServerFails(true)
        .build();
  }

  /// Builds the Market data agent around the chat model and its MCP tools.
  ///
  /// A failed tool call (an MCP result with `isError: true`) is sent to the model as the tool's
  /// result text, which is the AiServices default. Setting the handler explicitly only drops the
  /// default's unstructured warning; `ToolExecutionLogger` already logs the failure.
  ///
  /// @param marketDataChatModel chat model used by the agent
  /// @param marketDataToolProvider the MCP tools on the agent's allowlist
  /// @param agentProperties per-agent settings
  /// @param toolExecutionLogger logs each tool call the agent makes
  /// @return the Market data agent
  @Bean
  public MarketDataAgent marketDataAgent(
      ChatModel marketDataChatModel,
      ToolProvider marketDataToolProvider,
      AgentProperties agentProperties,
      ToolExecutionLogger toolExecutionLogger) {
    return AiServices.builder(MarketDataAgent.class)
        .chatModel(marketDataChatModel)
        .toolProvider(marketDataToolProvider)
        .toolExecutionErrorHandler(ToolExecutionErrorHandler.sendExceptionMessageToLlm())
        .afterToolExecution(toolExecutionLogger)
        .maxSequentialToolsInvocations(agentProperties.forAgent(MARKET_DATA_AGENT_NAME).maxSteps())
        .build();
  }
}
