package info.saagar.stockagent.config;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import dev.langchain4j.service.tool.ToolProvider;
import info.saagar.stockagent.agent.FilingsAgent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/// Manual LangChain4j wiring for the Filings analyst (no Spring Boot starter).
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class FilingsAgentConfig {

  /// Key of the Filings analyst's settings in `application.yml`; also logged as its agent name.
  public static final String FILINGS_AGENT_NAME = "filings";

  /// Builds the Claude chat model for the Filings analyst from its `application.yml` settings.
  ///
  /// @param agentProperties per-agent settings
  /// @param anthropicApiKey API key from the `ANTHROPIC_API_KEY` environment variable
  /// @return the chat model
  @Bean
  public ChatModel filingsChatModel(
      AgentProperties agentProperties, @Value("${ANTHROPIC_API_KEY}") String anthropicApiKey) {
    AgentProperties.AgentSettings filingsSettings = agentProperties.forAgent(FILINGS_AGENT_NAME);
    return AnthropicChatModel.builder()
        .apiKey(anthropicApiKey)
        .modelName(filingsSettings.model())
        .maxTokens(filingsSettings.maxTokens())
        .timeout(filingsSettings.timeout())
        .build();
  }

  /// Builds the Filings analyst's tool provider: the filings tools on its allowlist, and no others.
  ///
  /// @param filingsMcpClient client for `filings-mcp`
  /// @param agentProperties per-agent settings holding the allowlist
  /// @return the tool provider
  @Bean
  public ToolProvider filingsToolProvider(
      McpClient filingsMcpClient, AgentProperties agentProperties) {
    return McpToolProvider.builder()
        .mcpClients(filingsMcpClient)
        .filterToolNames(agentProperties.forAgent(FILINGS_AGENT_NAME).allowedTools())
        .failIfOneServerFails(true)
        .build();
  }

  /// Builds the Filings analyst around the chat model and its MCP tools.
  ///
  /// A failed tool call is sent to the model as the tool's result text, as for the Market data
  /// agent.
  ///
  /// @param filingsChatModel chat model used by the agent
  /// @param filingsToolProvider the MCP tools on the agent's allowlist
  /// @param agentProperties per-agent settings
  /// @return the Filings analyst
  @Bean
  public FilingsAgent filingsAgent(
      ChatModel filingsChatModel,
      ToolProvider filingsToolProvider,
      AgentProperties agentProperties) {
    return AgenticServices.agentBuilder(FilingsAgent.class)
        .chatModel(filingsChatModel)
        .toolProvider(filingsToolProvider)
        .toolExecutionErrorHandler(ToolExecutionErrorHandler.sendExceptionMessageToLlm())
        .maxSequentialToolsInvocations(agentProperties.forAgent(FILINGS_AGENT_NAME).maxSteps())
        .build();
  }
}
