package info.saagar.stockagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import info.saagar.stockagent.testing.FakeMcpServer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/// Connects the real MCP client wiring to an in-process MCP server that lists several tools.
class McpClientConfigTest {

  private static final ToolProviderRequest TOOL_PROVIDER_REQUEST =
      new ToolProviderRequest("memory-1", UserMessage.from("price of Apple"));

  private final McpClientConfig mcpClientConfig = new McpClientConfig();
  private final FakeMcpServer fakeMcpServer =
      FakeMcpServer.start("getQuote", "getCompanyProfile", "getCompanyNews");
  private final McpClient marketDataMcpClient =
      mcpClientConfig.marketDataMcpClient(
          new McpServerProperties(
              Map.of(
                  McpClientConfig.MARKET_DATA_SERVER_NAME,
                  new McpServerProperties.McpServerSettings(
                      fakeMcpServer.url(), "2025-11-25", Duration.ofSeconds(5)))));

  @AfterEach
  void closeClientAndServer() throws Exception {
    MDC.clear();
    marketDataMcpClient.close();
    fakeMcpServer.close();
  }

  private ToolProviderResult quoteToolsAllowing(List<String> allowedTools) {
    AgentProperties agentProperties =
        new AgentProperties(
            Map.of(
                QuoteAgentConfig.QUOTE_AGENT_NAME,
                new AgentProperties.AgentSettings(
                    "claude-haiku-5-5", 1024, Duration.ofSeconds(30), 4, allowedTools)));
    return new QuoteAgentConfig()
        .quoteToolProvider(marketDataMcpClient, agentProperties)
        .provideTools(TOOL_PROVIDER_REQUEST);
  }

  @Test
  void quoteAgentSeesOnlyTheToolsOnItsAllowlist() {
    ToolProviderResult quoteTools = quoteToolsAllowing(List.of("getQuote"));

    assertThat(quoteTools.tools().keySet())
        .extracting(ToolSpecification::name)
        .containsExactly("getQuote");
  }

  @Test
  void emptyAllowlistMeansNoTools() {
    assertThat(quoteToolsAllowing(List.of()).tools()).isEmpty();
  }

  @Test
  void toolCallCarriesTheRequestCorrelationHeaders() {
    ToolExecutor getQuoteExecutor =
        quoteToolsAllowing(List.of("getQuote")).toolExecutorByName("getQuote");
    MDC.put("correlationId", "corr-123");
    MDC.put("traceId", "trace-456");

    String quoteText =
        getQuoteExecutor.execute(
            ToolExecutionRequest.builder()
                .id("call-1")
                .name("getQuote")
                .arguments("{\"symbol\":\"AAPL\"}")
                .build(),
            "memory-1");

    assertThat(quoteText).isEqualTo(FakeMcpServer.AAPL_QUOTE_TEXT);
    assertThat(fakeMcpServer.toolCallHeaders())
        .containsExactly(Map.of("X-Correlation-Id", "corr-123", "X-Trace-Id", "trace-456"));
  }
}
