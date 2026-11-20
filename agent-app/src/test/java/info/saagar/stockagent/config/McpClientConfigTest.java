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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

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
    SecurityContextHolder.clearContext();
    marketDataMcpClient.close();
    fakeMcpServer.close();
  }

  private ToolProviderResult marketDataToolsAllowing(List<String> allowedTools) {
    AgentProperties agentProperties =
        new AgentProperties(
            Map.of(
                MarketDataAgentConfig.MARKET_DATA_AGENT_NAME,
                new AgentProperties.AgentSettings(
                    "claude-haiku-5-5", 1024, Duration.ofSeconds(30), 4, allowedTools)));
    return new MarketDataAgentConfig()
        .marketDataToolProvider(marketDataMcpClient, agentProperties)
        .provideTools(TOOL_PROVIDER_REQUEST);
  }

  @Test
  void marketDataAgentSeesOnlyTheToolsOnItsAllowlist() {
    ToolProviderResult marketDataTools = marketDataToolsAllowing(List.of("getQuote"));

    assertThat(marketDataTools.tools().keySet())
        .extracting(ToolSpecification::name)
        .containsExactly("getQuote");
  }

  @Test
  void emptyAllowlistMeansNoTools() {
    assertThat(marketDataToolsAllowing(List.of()).tools()).isEmpty();
  }

  @Test
  void toolCallCarriesTheRequestCorrelationHeaders() {
    ToolExecutor getQuoteExecutor =
        marketDataToolsAllowing(List.of("getQuote")).toolExecutorByName("getQuote");
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

  private static void signInAs(String bearerToken) {
    Jwt accessToken =
        Jwt.withTokenValue(bearerToken).header("alg", "RS256").subject("user_test_1").build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(accessToken));
  }

  @Test
  void marketDataToolCallForwardsTheUsersBearerToken() {
    ToolExecutor getQuoteExecutor =
        marketDataToolsAllowing(List.of("getQuote")).toolExecutorByName("getQuote");
    MDC.put("correlationId", "corr-123");
    signInAs("token-abc");

    getQuoteExecutor.execute(
        ToolExecutionRequest.builder()
            .id("call-1")
            .name("getQuote")
            .arguments("{\"symbol\":\"AAPL\"}")
            .build(),
        "memory-1");

    assertThat(fakeMcpServer.toolCallHeaders())
        .containsExactly(
            Map.of("X-Correlation-Id", "corr-123", "Authorization", "Bearer token-abc"));
  }

  @Test
  void portfolioToolCallForwardsTheUsersBearerTokenAndCorrelationId() throws Exception {
    try (FakeMcpServer fakePortfolioMcp = FakeMcpServer.start("getHoldings");
        McpClient portfolioMcpClient =
            mcpClientConfig.portfolioMcpClient(
                new McpServerProperties(
                    Map.of(
                        McpClientConfig.PORTFOLIO_SERVER_NAME,
                        new McpServerProperties.McpServerSettings(
                            fakePortfolioMcp.url(), "2025-11-25", Duration.ofSeconds(5)))))) {
      MDC.put("correlationId", "corr-123");
      signInAs("token-abc");

      String holdingsText =
          portfolioMcpClient
              .executeTool(
                  ToolExecutionRequest.builder()
                      .id("call-1")
                      .name("getHoldings")
                      .arguments("{}")
                      .build())
              .resultText();

      assertThat(holdingsText).isEqualTo(FakeMcpServer.HOLDINGS_TEXT);
      assertThat(fakePortfolioMcp.toolCallHeaders())
          .containsExactly(
              Map.of("X-Correlation-Id", "corr-123", "Authorization", "Bearer token-abc"));
    }
  }
}
