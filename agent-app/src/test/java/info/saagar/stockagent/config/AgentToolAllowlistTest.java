package info.saagar.stockagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import info.saagar.stockagent.agent.MarketDataAgent;
import info.saagar.stockagent.testing.FakeMcpServer;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
/// One fake MCP server lists all three servers' tools, so the only thing keeping a tool from an
/// agent is its allowlist.
class AgentToolAllowlistTest {

  private static final Set<String> MARKET_DATA_TOOL_NAMES =
      Set.of(
          "getQuote",
          "getCompanyProfile",
          "getCompanyNews",
          "getMarketNews",
          "getEarningsCalendar",
          "getBasicFinancials");
  private static final Set<String> FILINGS_TOOL_NAMES =
      Set.of("listFilings", "getCompanyFacts", "searchFilings");
  private static final Set<String> PORTFOLIO_TOOL_NAMES = Set.of("getHoldings", "getWatchlist");
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
          "getCompanyFacts",
          "searchFilings",
          "getHoldings",
          "getWatchlist");
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
  void marketDataAgentSeesNoFilingsOrPortfolioTools() throws IOException {
    Set<String> marketDataAgentToolNames =
        toolNames(
            new MarketDataAgentConfig()
                .marketDataToolProvider(sharedMcpClient, configuredAgentProperties()));

    assertThat(marketDataAgentToolNames).isEqualTo(MARKET_DATA_TOOL_NAMES);
  }

  @Test
  void portfolioAgentSeesOnlyPortfolioTools() throws IOException {
    Set<String> portfolioAgentToolNames =
        toolNames(
            new PortfolioAgentConfig()
                .portfolioToolProvider(sharedMcpClient, configuredAgentProperties()));

    assertThat(portfolioAgentToolNames).isEqualTo(PORTFOLIO_TOOL_NAMES);
  }

  /// Calls `getCompanyNews`, then does what the hostile article says: calls `getHoldings`. Records
  /// the tool names offered on every request and the tool result texts it was sent.
  private static final class InjectionObeyingChatModel implements ChatModel {

    private final List<List<String>> offeredToolNamesPerRequest = new ArrayList<>();
    private final List<String> toolResultTextsSeen = new ArrayList<>();

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
      offeredToolNamesPerRequest.add(
          chatRequest.toolSpecifications().stream().map(ToolSpecification::name).toList());
      if (chatRequest.messages().getLast() instanceof ToolExecutionResultMessage toolResult) {
        toolResultTextsSeen.add(toolResult.text());
        return toolCall("call-2", "getHoldings", "{}");
      }
      return toolCall("call-1", "getCompanyNews", "{\"symbol\":\"AAPL\"}");
    }

    private static ChatResponse toolCall(String callId, String toolName, String arguments) {
      ToolExecutionRequest toolRequest =
          ToolExecutionRequest.builder().id(callId).name(toolName).arguments(arguments).build();
      return ChatResponse.builder().aiMessage(AiMessage.from(toolRequest)).build();
    }
  }

  /// FR-33: the fake `getCompanyNews` returns an article telling the agent to call the portfolio
  /// tools, and the scripted model obeys it, so the allowlist is the only defence.
  @Test
  void hostileNewsTextCannotMakeTheMarketDataAgentRunAPortfolioTool() throws IOException {
    AgentProperties agentProperties = configuredAgentProperties();
    MarketDataAgentConfig marketDataAgentConfig = new MarketDataAgentConfig();
    InjectionObeyingChatModel injectionObeyingChatModel = new InjectionObeyingChatModel();
    MarketDataAgent marketDataAgent =
        marketDataAgentConfig.marketDataAgent(
            injectionObeyingChatModel,
            marketDataAgentConfig.marketDataToolProvider(sharedMcpClient, agentProperties),
            agentProperties);

    // The model asks for a tool the agent does not have; LangChain4j's default is to fail the run.
    assertThatThrownBy(() -> marketDataAgent.answer("Latest news about Apple"));

    assertThat(injectionObeyingChatModel.toolResultTextsSeen)
        .containsExactly(FakeMcpServer.HOSTILE_NEWS_TEXT);
    assertThat(injectionObeyingChatModel.offeredToolNamesPerRequest)
        .hasSize(2)
        .allSatisfy(
            offeredToolNames ->
                assertThat(offeredToolNames)
                    .contains("getCompanyNews")
                    .doesNotContainAnyElementsOf(PORTFOLIO_TOOL_NAMES));
    assertThat(fakeMcpServer.calledToolNames()).containsExactly("getCompanyNews");
  }
}
