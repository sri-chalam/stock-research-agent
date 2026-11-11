package info.saagar.stockagent.controller;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.model.chat.ChatModel;
import info.saagar.stockagent.controller.ChatController.ChatResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "ANTHROPIC_API_KEY=test-key")
@Import(ChatControllerTest.FakeModelConfig.class)
class ChatControllerTest {

  @TestConfiguration
  static class FakeModelConfig {
    @Bean
    @Primary
    ChatModel scriptedChatModel() {
      return new SwitchableQuoteChatModel();
    }
  }

  /// Delegates to a scripted model whose requested symbol is derived from the user's prompt.
  static class SwitchableQuoteChatModel implements ChatModel {
    @Override
    public dev.langchain4j.model.chat.response.ChatResponse doChat(
        dev.langchain4j.model.chat.request.ChatRequest chatRequest) {
      boolean asksAboutUnknownCompany =
          chatRequest.messages().stream()
              .anyMatch(message -> message.toString().contains("Zorblax"));
      String symbolToRequest = asksAboutUnknownCompany ? "XYZ" : "AAPL";
      return new ScriptedQuoteChatModel(symbolToRequest).doChat(chatRequest);
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
              assertThat(toolCall.name()).isEqualTo("getQuote");
              assertThat(toolCall.arguments()).containsEntry("symbol", "AAPL");
            });
  }

  @Test
  void unknownSymbolRelaysReadableMessage() throws Exception {
    ChatResponse response = postChat("price of Zorblax");

    assertThat(response.answer())
        .contains("No quote available for symbol XYZ. Only US equities are supported.");
    assertThat(response.toolsCalled())
        .extracting(ChatController.ToolCall::name)
        .containsExactly("getQuote");
  }
}
