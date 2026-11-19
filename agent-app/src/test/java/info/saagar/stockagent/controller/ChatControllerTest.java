package info.saagar.stockagent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import info.saagar.stockagent.common.testing.TestJwts;
import info.saagar.stockagent.config.FilingsAgentConfig;
import info.saagar.stockagent.config.MarketDataAgentConfig;
import info.saagar.stockagent.config.PortfolioAgentConfig;
import info.saagar.stockagent.controller.ChatController.ChatResponse;
import info.saagar.stockagent.testing.FakeMcpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"ANTHROPIC_API_KEY=test-key", "stock-agent.semantic-cache.store=in-memory"})
@Import(ChatControllerTest.FakeModelConfig.class)
class ChatControllerTest {

  private static final FakeMcpServer FAKE_MARKET_DATA_MCP = FakeMcpServer.start("getQuote");
  private static final FakeMcpServer FAKE_FILINGS_MCP = FakeMcpServer.start("listFilings");
  private static final FakeMcpServer FAKE_PORTFOLIO_MCP =
      FakeMcpServer.start("getHoldings", "getWatchlist");

  @DynamicPropertySource
  static void pointAgentAtFakeMcpServer(DynamicPropertyRegistry propertyRegistry) {
    propertyRegistry.add("stock-agent.mcp-servers.market-data.url", FAKE_MARKET_DATA_MCP::url);
    propertyRegistry.add("stock-agent.mcp-servers.filings.url", FAKE_FILINGS_MCP::url);
    propertyRegistry.add("stock-agent.mcp-servers.portfolio.url", FAKE_PORTFOLIO_MCP::url);
    TestJwts.registerResourceServerProperties(propertyRegistry);
  }

  private static final String USER_1_SUB = "user_test_1";
  private static final String USER_1_TOKEN = TestJwts.token(USER_1_SUB, "agent.chat");

  @TestConfiguration
  static class FakeModelConfig {
    @Bean
    @Primary
    ChatModel scriptedChatModel() {
      return new PromptRoutedChatModel();
    }
  }

  /// Delegates to a scripted model chosen from the user's prompt: a revenue question goes to the
  /// Filings analyst then the Market data agent, a "doing" question to the Portfolio agent then the
  /// Market data agent, a "hold" question to the Portfolio agent, anything else to the Market data
  /// agent. Only user messages are matched, because the planner's system message mentions revenue
  /// and holdings, and Zorblax asks for an unknown symbol. Records every request's full message
  /// text, so a test can check what reached the model.
  static class PromptRoutedChatModel implements ChatModel {

    static final List<String> PROMPT_TEXTS = new CopyOnWriteArrayList<>();

    @Override
    public dev.langchain4j.model.chat.response.ChatResponse doChat(
        dev.langchain4j.model.chat.request.ChatRequest chatRequest) {
      PROMPT_TEXTS.add(chatRequest.messages().toString());
      String conversationText =
          chatRequest.messages().stream()
              .filter(UserMessage.class::isInstance)
              .map(ChatMessage::toString)
              .collect(Collectors.joining());
      String symbolToRequest = conversationText.contains("Zorblax") ? "XYZ" : "AAPL";
      ScriptedAgentsChatModel scriptedModel;
      if (conversationText.contains("revenue")) {
        scriptedModel = ScriptedAgentsChatModel.filingsThenMarketData(symbolToRequest);
      } else if (conversationText.contains("doing")) {
        scriptedModel = ScriptedAgentsChatModel.portfolioThenMarketData(symbolToRequest);
      } else if (conversationText.contains("hold")) {
        scriptedModel = ScriptedAgentsChatModel.portfolioOnly();
      } else {
        scriptedModel = ScriptedAgentsChatModel.marketDataOnly(symbolToRequest);
      }
      return scriptedModel.doChat(chatRequest);
    }
  }

  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  @LocalServerPort private int port;
  @Autowired private JsonMapper jsonMapper;

  private HttpResponse<String> sendChat(String message, Map<String, String> requestHeaders)
      throws Exception {
    HttpRequest.Builder chatRequestBuilder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/chat"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    jsonMapper.writeValueAsString(new ChatController.ChatRequest(message))));
    requestHeaders.forEach(chatRequestBuilder::header);
    return HTTP_CLIENT.send(chatRequestBuilder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static Map<String, String> bearer(String accessToken) {
    return Map.of("Authorization", "Bearer " + accessToken);
  }

  /// Posts as demo user 1, with `agent.chat`, and expects 200.
  private ChatResponse postChat(String message, Map<String, String> extraHeaders) throws Exception {
    Map<String, String> requestHeaders = new HashMap<>(extraHeaders);
    requestHeaders.putAll(bearer(USER_1_TOKEN));
    HttpResponse<String> httpResponse = sendChat(message, requestHeaders);
    assertThat(httpResponse.statusCode()).isEqualTo(200);
    return jsonMapper.readValue(httpResponse.body(), ChatResponse.class);
  }

  private ChatResponse postChat(String message) throws Exception {
    return postChat(message, Map.of());
  }

  @Test
  void priceOfAppleCallsGetQuoteAndReturnsFakePrice() throws Exception {
    ChatResponse response = postChat("price of Apple");

    assertThat(response.answer()).contains("189.42");
    assertThat(response.toolsCalled())
        .singleElement()
        .satisfies(
            toolCall -> {
              assertThat(toolCall.agentName())
                  .isEqualTo(MarketDataAgentConfig.MARKET_DATA_AGENT_NAME);
              assertThat(toolCall.name()).isEqualTo("getQuote");
              assertThat(toolCall.arguments()).containsEntry("symbol", "AAPL");
            });
  }

  @Test
  void unknownSymbolRelaysReadableMessage() throws Exception {
    ChatResponse response = postChat("price of Zorblax");

    assertThat(response.answer())
        .contains("No quote available for symbol XYZ. Only US-listed equities are supported.");
    assertThat(response.toolsCalled())
        .extracting(ChatController.ToolCall::name)
        .containsExactly("getQuote");
  }

  @Test
  void combinedQuestionRunsBothSpecialistsAndMergesTheirAnswers() throws Exception {
    ChatResponse response = postChat("Apple's revenue for the last 3 fiscal years and its price");

    assertThat(response.answer()).startsWith("Merged: ").contains(" | ");
    assertThat(response.toolsCalled())
        .extracting(ChatController.ToolCall::agentName, ChatController.ToolCall::name)
        .containsExactly(
            tuple(FilingsAgentConfig.FILINGS_AGENT_NAME, "listFilings"),
            tuple(MarketDataAgentConfig.MARKET_DATA_AGENT_NAME, "getQuote"));
  }

  @Test
  void holdingsQuestionForwardsTheBearerTokenToPortfolioMcpButNotIntoAnyPrompt() throws Exception {
    String correlationId = "corr-holdings-user-1";

    ChatResponse response =
        postChat("What stocks do I hold?", Map.of("X-Correlation-Id", correlationId));

    assertThat(response.answer()).contains(FakeMcpServer.HOLDINGS_TEXT);
    assertThat(response.toolsCalled())
        .extracting(ChatController.ToolCall::agentName, ChatController.ToolCall::name)
        .containsExactly(tuple(PortfolioAgentConfig.PORTFOLIO_AGENT_NAME, "getHoldings"));
    assertThat(FAKE_PORTFOLIO_MCP.toolCallHeaders())
        .filteredOn(
            toolCallHeaders -> correlationId.equals(toolCallHeaders.get("X-Correlation-Id")))
        .singleElement()
        .satisfies(
            toolCallHeaders ->
                assertThat(toolCallHeaders)
                    .containsEntry("Authorization", "Bearer " + USER_1_TOKEN));
    assertThat(PromptRoutedChatModel.PROMPT_TEXTS)
        .isNotEmpty()
        .noneMatch(promptText -> promptText.contains(USER_1_TOKEN))
        .noneMatch(promptText -> promptText.contains(USER_1_SUB));
  }

  @Test
  void holdingsPerformanceQuestionChainsPortfolioIntoMarketDataForwardingTheTokenToBoth()
      throws Exception {
    String correlationId = "corr-holdings-doing";

    ChatResponse response =
        postChat("How are my holdings doing today?", Map.of("X-Correlation-Id", correlationId));

    assertThat(response.toolsCalled())
        .extracting(ChatController.ToolCall::agentName, ChatController.ToolCall::name)
        .containsExactly(
            tuple(PortfolioAgentConfig.PORTFOLIO_AGENT_NAME, "getHoldings"),
            tuple(MarketDataAgentConfig.MARKET_DATA_AGENT_NAME, "getQuote"));
    assertThat(FAKE_MARKET_DATA_MCP.toolCallHeaders())
        .filteredOn(
            toolCallHeaders -> correlationId.equals(toolCallHeaders.get("X-Correlation-Id")))
        .isNotEmpty()
        .allSatisfy(
            toolCallHeaders ->
                assertThat(toolCallHeaders)
                    .containsEntry("Authorization", "Bearer " + USER_1_TOKEN));
  }

  @Test
  void chatWithoutAValidTokenIsRejectedWith401() throws Exception {
    assertThat(sendChat("price of Apple", Map.of()).statusCode()).isEqualTo(401);
    assertThat(
            sendChat("price of Apple", bearer(TestJwts.expiredToken(USER_1_SUB, "agent.chat")))
                .statusCode())
        .isEqualTo(401);
    assertThat(
            sendChat(
                    "price of Apple",
                    bearer(
                        TestJwts.tokenFromIssuer(
                            "https://evil.test.invalid", USER_1_SUB, "agent.chat")))
                .statusCode())
        .isEqualTo(401);
  }

  @Test
  void chatWithoutAgentChatIsRejectedWith403() throws Exception {
    HttpResponse<String> httpResponse =
        sendChat("price of Apple", bearer(TestJwts.token(USER_1_SUB, "portfolio.read")));

    assertThat(httpResponse.statusCode()).isEqualTo(403);
  }
}
