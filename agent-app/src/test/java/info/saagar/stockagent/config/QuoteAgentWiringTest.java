package info.saagar.stockagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import info.saagar.stockagent.agent.QuoteAgent;
import info.saagar.stockagent.logging.ToolExecutionLogger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/// Drives the Quote agent built by [QuoteAgentConfig] with a scripted model and an in-memory tool
/// provider standing in for the MCP server.
class QuoteAgentWiringTest {

  private static final String AAPL_QUOTE_JSON = "{\"symbol\":\"AAPL\",\"currentPrice\":189.42}";
  private static final String NO_QUOTE_MESSAGE =
      "No quote available for symbol XYZ. Only US-listed equities are supported.";

  private static final ToolSpecification GET_QUOTE_SPECIFICATION =
      ToolSpecification.builder()
          .name("getQuote")
          .description("Get the latest price quote for a US-listed stock.")
          .parameters(JsonObjectSchema.builder().addStringProperty("symbol").build())
          .build();

  /// Like the MCP tool executor: returns the quote for AAPL and raises a tool error otherwise.
  private static final ToolProvider FAKE_MARKET_DATA_TOOLS =
      toolProviderRequest ->
          ToolProviderResult.builder()
              .add(
                  GET_QUOTE_SPECIFICATION,
                  (toolRequest, memoryId) -> {
                    if (toolRequest.arguments().contains("\"AAPL\"")) {
                      return AAPL_QUOTE_JSON;
                    }
                    throw new ToolExecutionException(NO_QUOTE_MESSAGE, (Integer) null);
                  })
              .build();

  private static final AgentProperties AGENT_PROPERTIES =
      new AgentProperties(
          Map.of(
              QuoteAgentConfig.QUOTE_AGENT_NAME,
              new AgentProperties.AgentSettings(
                  "claude-haiku-5-5", 1024, Duration.ofSeconds(30), 4, List.of("getQuote"))));

  /// Asks for `getQuote(symbol)`, then answers with the tool result text it was given.
  private static final class RecordingQuoteChatModel implements ChatModel {

    private final String symbolToRequest;
    private final List<String> toolResultTextsSeen = new ArrayList<>();

    private RecordingQuoteChatModel(String symbolToRequest) {
      this.symbolToRequest = symbolToRequest;
    }

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
      List<ChatMessage> messages = chatRequest.messages();
      if (messages.getLast() instanceof ToolExecutionResultMessage toolResult) {
        toolResultTextsSeen.add(toolResult.text());
        return ChatResponse.builder()
            .aiMessage(AiMessage.from("Tool said: " + toolResult.text()))
            .build();
      }
      assertThat(messages.getLast()).isInstanceOf(UserMessage.class);
      assertThat(chatRequest.toolSpecifications())
          .extracting(ToolSpecification::name)
          .containsExactly("getQuote");
      ToolExecutionRequest getQuoteRequest =
          ToolExecutionRequest.builder()
              .id("call-1")
              .name("getQuote")
              .arguments("{\"symbol\":\"" + symbolToRequest + "\"}")
              .build();
      return ChatResponse.builder().aiMessage(AiMessage.from(getQuoteRequest)).build();
    }
  }

  private static QuoteAgent quoteAgent(ChatModel chatModel) {
    return new QuoteAgentConfig()
        .quoteAgent(
            chatModel,
            FAKE_MARKET_DATA_TOOLS,
            AGENT_PROPERTIES,
            new ToolExecutionLogger(JsonMapper.builder().build()));
  }

  @Test
  void callsGetQuoteAndAnswersFromItsResult() {
    RecordingQuoteChatModel chatModel = new RecordingQuoteChatModel("AAPL");

    Result<String> agentResult = quoteAgent(chatModel).answer("price of Apple");

    assertThat(agentResult.content()).isEqualTo("Tool said: " + AAPL_QUOTE_JSON);
    assertThat(agentResult.toolExecutions())
        .singleElement()
        .satisfies(
            toolExecution -> {
              assertThat(toolExecution.request().name()).isEqualTo("getQuote");
              assertThat(toolExecution.hasFailed()).isFalse();
            });
  }

  @Test
  void toolErrorTextIsSentToTheModel() {
    RecordingQuoteChatModel chatModel = new RecordingQuoteChatModel("XYZ");

    Result<String> agentResult = quoteAgent(chatModel).answer("price of Zorblax");

    assertThat(chatModel.toolResultTextsSeen).containsExactly(NO_QUOTE_MESSAGE);
    assertThat(agentResult.content()).isEqualTo("Tool said: " + NO_QUOTE_MESSAGE);
    assertThat(agentResult.toolExecutions())
        .extracting(ToolExecution::hasFailed)
        .containsExactly(true);
  }
}
