package info.saagar.stockagent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import info.saagar.stockagent.config.FilingsAgentConfig;
import info.saagar.stockagent.config.MarketDataAgentConfig;
import info.saagar.stockagent.controller.ChatController.ChatResponse;
import info.saagar.stockagent.testing.FakeMcpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    properties = "ANTHROPIC_API_KEY=test-key")
@Import(ChatControllerTest.FakeModelConfig.class)
class ChatControllerTest {

  private static final FakeMcpServer FAKE_MARKET_DATA_MCP = FakeMcpServer.start("getQuote");
  private static final FakeMcpServer FAKE_FILINGS_MCP = FakeMcpServer.start("listFilings");

  @DynamicPropertySource
  static void pointAgentAtFakeMcpServer(DynamicPropertyRegistry propertyRegistry) {
    propertyRegistry.add("stock-agent.mcp-servers.market-data.url", FAKE_MARKET_DATA_MCP::url);
    propertyRegistry.add("stock-agent.mcp-servers.filings.url", FAKE_FILINGS_MCP::url);
  }

  @TestConfiguration
  static class FakeModelConfig {
    @Bean
    @Primary
    ChatModel scriptedChatModel() {
      return new PromptRoutedChatModel();
    }
  }

  /// Delegates to a scripted model chosen from the user's prompt: a revenue question goes to both
  /// specialists, anything else to the Market data agent. Only user messages are matched, because
  /// the planner's system message mentions revenue, and Zorblax asks for an unknown symbol.
  static class PromptRoutedChatModel implements ChatModel {
    @Override
    public dev.langchain4j.model.chat.response.ChatResponse doChat(
        dev.langchain4j.model.chat.request.ChatRequest chatRequest) {
      String conversationText =
          chatRequest.messages().stream()
              .filter(UserMessage.class::isInstance)
              .map(ChatMessage::toString)
              .collect(Collectors.joining());
      String symbolToRequest = conversationText.contains("Zorblax") ? "XYZ" : "AAPL";
      ScriptedAgentsChatModel scriptedModel =
          conversationText.contains("revenue")
              ? ScriptedAgentsChatModel.filingsThenMarketData(symbolToRequest)
              : ScriptedAgentsChatModel.marketDataOnly(symbolToRequest);
      return scriptedModel.doChat(chatRequest);
    }
  }

  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  @LocalServerPort private int port;
  @Autowired private JsonMapper jsonMapper;

  private ChatResponse postChat(String message) throws Exception {
    HttpRequest chatHttpRequest =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/chat"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    jsonMapper.writeValueAsString(new ChatController.ChatRequest(message))))
            .build();
    HttpResponse<String> httpResponse =
        HTTP_CLIENT.send(chatHttpRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(httpResponse.statusCode()).isEqualTo(200);
    return jsonMapper.readValue(httpResponse.body(), ChatResponse.class);
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
}
