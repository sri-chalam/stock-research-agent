package info.saagar.stockagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import info.saagar.stockagent.testing.FakeMcpServer;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/// Each agent sees only the tools on its allowlist in `application.yml`.
///
/// One fake MCP server lists both servers' tools, so the only thing keeping a tool from an agent is
/// its allowlist.
class AgentToolAllowlistTest {

  private static final Set<String> MARKET_DATA_TOOL_NAMES =
      Set.of(
          "getQuote",
          "getCompanyProfile",
          "getCompanyNews",
          "getMarketNews",
          "getEarningsCalendar",
          "getBasicFinancials");
  private static final Set<String> FILINGS_TOOL_NAMES = Set.of("listFilings", "getCompanyFacts");
  private static final ToolProviderRequest TOOL_PROVIDER_REQUEST =
      new ToolProviderRequest("memory-1", UserMessage.from("List Microsoft's recent 10-K filings"));

  private final FakeMcpServer fakeMcpServer =
      FakeMcpServer.start(
          "getQuote",
          "getCompanyProfile",
          "getCompanyNews",
          "getMarketNews",
          "getEarningsCalendar",
          "getBasicFinancials",
          "listFilings",
          "getCompanyFacts");
  private final McpClient sharedMcpClient =
      new McpClientConfig()
          .filingsMcpClient(
              new McpServerProperties(
                  Map.of(
                      McpClientConfig.FILINGS_SERVER_NAME,
                      new McpServerProperties.McpServerSettings(
                          fakeMcpServer.url(), "2025-11-25", Duration.ofSeconds(5)))));

  @AfterEach
  void closeClientAndServer() throws Exception {
    sharedMcpClient.close();
    fakeMcpServer.close();
  }

  private static AgentProperties configuredAgentProperties() throws IOException {
    Binder applicationYmlBinder =
        new Binder(
            ConfigurationPropertySources.from(
                new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"))));
    return applicationYmlBinder.bind("stock-agent", AgentProperties.class).get();
  }

  private static Set<String> toolNames(ToolProvider toolProvider) {
    return toolProvider.provideTools(TOOL_PROVIDER_REQUEST).tools().keySet().stream()
        .map(ToolSpecification::name)
        .collect(Collectors.toSet());
  }

  @Test
  void filingsAnalystSeesOnlyFilingsTools() throws IOException {
    Set<String> filingsAgentToolNames =
        toolNames(
            new FilingsAgentConfig()
                .filingsToolProvider(sharedMcpClient, configuredAgentProperties()));

    assertThat(filingsAgentToolNames).isEqualTo(FILINGS_TOOL_NAMES);
  }

  @Test
  void marketDataAgentSeesNoFilingsTools() throws IOException {
    Set<String> marketDataAgentToolNames =
        toolNames(
            new MarketDataAgentConfig()
                .marketDataToolProvider(sharedMcpClient, configuredAgentProperties()));

    assertThat(marketDataAgentToolNames).isEqualTo(MARKET_DATA_TOOL_NAMES);
  }
}
