package info.saagar.stockagent.config;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import dev.langchain4j.service.tool.ToolProvider;
import info.saagar.stockagent.agent.PortfolioAgent;
import info.saagar.stockagent.logging.AgentProvenanceListener;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/// Manual LangChain4j wiring for the Portfolio agent (no Spring Boot starter).
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class PortfolioAgentConfig {

  /// Key of the Portfolio agent's settings in `application.yml`; also logged as its agent name.
  public static final String PORTFOLIO_AGENT_NAME = "portfolio";

  /// Builds the Claude chat model for the Portfolio agent from its `application.yml` settings.
  ///
  /// @param agentProperties per-agent settings
  /// @param anthropicApiKey API key from the `ANTHROPIC_API_KEY` environment variable
  /// @param agentProvenanceListener counts the model's tokens per run
  /// @return the chat model
  @Bean
  public ChatModel portfolioChatModel(
      AgentProperties agentProperties,
      @Value("${ANTHROPIC_API_KEY}") String anthropicApiKey,
      AgentProvenanceListener agentProvenanceListener) {
    AgentProperties.AgentSettings portfolioSettings =
        agentProperties.forAgent(PORTFOLIO_AGENT_NAME);
    return AnthropicChatModel.builder()
        .apiKey(anthropicApiKey)
        .modelName(portfolioSettings.model())
        .maxTokens(portfolioSettings.maxTokens())
        .timeout(portfolioSettings.timeout())
        .listeners(List.of(agentProvenanceListener.tokenListener(PORTFOLIO_AGENT_NAME)))
        .build();
  }

  /// Builds the Portfolio agent's tool provider: the portfolio tools on its allowlist, and no
  /// others.
  ///
  /// @param portfolioMcpClient client for `portfolio-mcp`
  /// @param agentProperties per-agent settings holding the allowlist
  /// @return the tool provider
  @Bean
  public ToolProvider portfolioToolProvider(
      McpClient portfolioMcpClient, AgentProperties agentProperties) {
    return McpToolProvider.builder()
        .mcpClients(portfolioMcpClient)
        .filterToolNames(agentProperties.forAgent(PORTFOLIO_AGENT_NAME).allowedTools())
        .failIfOneServerFails(true)
        .build();
  }

  /// Builds the Portfolio agent around the chat model and its MCP tools.
  ///
  /// A failed tool call, such as "The portfolio is unavailable", is sent to the model as the tool's
  /// result text, as for the Market data agent.
  ///
  /// @param portfolioChatModel chat model used by the agent
  /// @param portfolioToolProvider the MCP tools on the agent's allowlist
  /// @param agentProperties per-agent settings
  /// @return the Portfolio agent
  @Bean
  public PortfolioAgent portfolioAgent(
      ChatModel portfolioChatModel,
      ToolProvider portfolioToolProvider,
      AgentProperties agentProperties) {
    return AgenticServices.agentBuilder(PortfolioAgent.class)
        .chatModel(portfolioChatModel)
        .toolProvider(portfolioToolProvider)
        .toolExecutionErrorHandler(ToolExecutionErrorHandler.sendExceptionMessageToLlm())
        .maxSequentialToolsInvocations(agentProperties.forAgent(PORTFOLIO_AGENT_NAME).maxSteps())
        .build();
  }
}
