package info.saagar.stockagent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import info.saagar.stockagent.agent.MarketDataAgent;
import info.saagar.stockagent.agent.PortfolioAgent;
import info.saagar.stockagent.config.MarketDataAgentConfig;
import info.saagar.stockagent.config.PortfolioAgentConfig;
import info.saagar.stockagent.config.PromptClassifierConfig;
import info.saagar.stockagent.config.SupervisorAgentConfig;
import info.saagar.stockagent.logging.AgentProvenanceListener;
import info.saagar.stockagent.testing.FakeMcpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/// Verifies the structured JSON log lines a `/api/chat` call writes to the console.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "ANTHROPIC_API_KEY=test-key")
@Import(ChatLoggingTest.FakeModelConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class ChatLoggingTest {

  private static final FakeMcpServer FAKE_MARKET_DATA_MCP = FakeMcpServer.start("getQuote");
  private static final FakeMcpServer FAKE_FILINGS_MCP = FakeMcpServer.start("listFilings");
  private static final FakeMcpServer FAKE_PORTFOLIO_MCP =
      FakeMcpServer.start("getHoldings", "getWatchlist");

  @DynamicPropertySource
  static void pointAgentAtFakeMcpServer(DynamicPropertyRegistry propertyRegistry) {
    propertyRegistry.add("stock-agent.mcp-servers.market-data.url", FAKE_MARKET_DATA_MCP::url);
    propertyRegistry.add("stock-agent.mcp-servers.filings.url", FAKE_FILINGS_MCP::url);
    propertyRegistry.add("stock-agent.mcp-servers.portfolio.url", FAKE_PORTFOLIO_MCP::url);
  }

  private static final String FAILING_PROMPT = "please fail";
  private static final String HOLDINGS_PERFORMANCE_PROMPT = "How are my holdings doing today?";
  private static final String FORCED_FAILURE_MESSAGE = "Forced model failure for logging test";
  private static final String CLASSIFIER_FAILING_PROMPT =
      "price of Apple while the classifier is down";
  private static final String CLASSIFIER_FAILURE_MESSAGE = "Forced classifier failure";
  private static final String INVALID_TICKER_PROMPT = "price of Apple with a bad ticker";
  private static final String UUID_PATTERN =
      "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
  private static final TypeReference<Map<String, Object>> LOG_LINE_TYPE = new TypeReference<>() {};
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  @TestConfiguration
  static class FakeModelConfig {
    @Bean
    @Primary
    ChatModel failableQuoteChatModel(AgentProvenanceListener agentProvenanceListener) {
      return new FailableQuoteChatModel(agentProvenanceListener);
    }
  }

  /// As the classifier: throws for the classifier-failing prompt, returns an invalid ticker for the
  /// invalid-ticker prompt, and otherwise returns `REAL_TIME` with `AAPL`. As the agents: throws
  /// for the failing prompt; routes the holdings-performance prompt to the Portfolio agent then the
  /// Market data agent; otherwise routes to the Market data agent, which asks for `getQuote(AAPL)`
  /// and relays the result.
  ///
  /// Like each agent's Anthropic chat model, it reports every response to the provenance listener's
  /// token listener for the agent that made the call.
  static class FailableQuoteChatModel implements ChatModel {

    private final AgentProvenanceListener agentProvenanceListener;

    FailableQuoteChatModel(AgentProvenanceListener agentProvenanceListener) {
      this.agentProvenanceListener = agentProvenanceListener;
    }

    @Override
    public List<ChatModelListener> listeners() {
      return List.of(
          new ChatModelListener() {
            @Override
            public void onResponse(ChatModelResponseContext responseContext) {
              agentProvenanceListener
                  .tokenListener(
                      ScriptedAgentsChatModel.agentNameFor(responseContext.chatRequest()))
                  .onResponse(responseContext);
            }
          });
    }

    @Override
    public dev.langchain4j.model.chat.response.ChatResponse doChat(
        dev.langchain4j.model.chat.request.ChatRequest chatRequest) {
      if (ScriptedAgentsChatModel.agentNameFor(chatRequest)
          .equals(PromptClassifierConfig.CLASSIFIER_NAME)) {
        return classify(chatRequest);
      }
      boolean asksToFail =
          chatRequest.messages().stream()
              .anyMatch(message -> message.toString().contains(FAILING_PROMPT));
      if (asksToFail) {
        throw new IllegalStateException(FORCED_FAILURE_MESSAGE);
      }
      boolean asksAboutHoldings =
          chatRequest.messages().stream()
              .anyMatch(message -> message.toString().contains(HOLDINGS_PERFORMANCE_PROMPT));
      return asksAboutHoldings
          ? ScriptedAgentsChatModel.portfolioThenMarketData("AAPL").doChat(chatRequest)
          : ScriptedAgentsChatModel.marketDataOnly("AAPL").doChat(chatRequest);
    }

    private static dev.langchain4j.model.chat.response.ChatResponse classify(
        dev.langchain4j.model.chat.request.ChatRequest chatRequest) {
      String requestText = chatRequest.messages().toString();
      if (requestText.contains(CLASSIFIER_FAILING_PROMPT)) {
        throw new IllegalStateException(CLASSIFIER_FAILURE_MESSAGE);
      }
      return requestText.contains(INVALID_TICKER_PROMPT)
          ? ScriptedAgentsChatModel.classifierReply("CACHEABLE", List.of("APPLE1"))
          : ScriptedAgentsChatModel.classifierReply("REAL_TIME", List.of("AAPL"));
    }
  }

  @LocalServerPort private int port;
  @Autowired private JsonMapper jsonMapper;

  private HttpResponse<String> postChat(String message, Map<String, String> requestHeaders)
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

  private List<Map<String, Object>> logLinesFor(
      CapturedOutput capturedOutput, String correlationId) {
    return capturedOutput
        .getOut()
        .lines()
        .filter(line -> line.startsWith("{"))
        .map(line -> jsonMapper.readValue(line, LOG_LINE_TYPE))
        .filter(logLine -> correlationId.equals(logLine.get("correlationId")))
        .toList();
  }

  private static Map<String, Object> singleEvent(
      List<Map<String, Object>> logLines, String eventName) {
    List<Map<String, Object>> matchingLines =
        logLines.stream().filter(logLine -> eventName.equals(logLine.get("eventName"))).toList();
    assertThat(matchingLines).as("log lines with eventName %s", eventName).hasSize(1);
    return matchingLines.getFirst();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> eventData(Map<String, Object> logLine) {
    return (Map<String, Object>) logLine.get("eventData");
  }

  @Test
  void providedCorrelationIdIsLoggedAndUnsafeTraceIdIsReplaced(CapturedOutput capturedOutput)
      throws Exception {
    String correlationId = "corr-" + UUID.randomUUID();

    HttpResponse<String> httpResponse =
        postChat(
            "price of Apple",
            Map.of("X-Correlation-Id", correlationId, "X-Trace-Id", "unsafe<script>"));

    assertThat(httpResponse.statusCode()).isEqualTo(200);
    List<Map<String, Object>> logLines = logLinesFor(capturedOutput, correlationId);
    assertThat(logLines).isNotEmpty();
    assertThat(logLines)
        .allSatisfy(logLine -> assertThat((String) logLine.get("traceId")).matches(UUID_PATTERN));
  }

  @Test
  void chatLogsToolExecutionAndAnswerProvenance(CapturedOutput capturedOutput) throws Exception {
    String correlationId = "corr-" + UUID.randomUUID();

    HttpResponse<String> httpResponse =
        postChat("price of Apple", Map.of("X-Correlation-Id", correlationId));

    assertThat(httpResponse.statusCode()).isEqualTo(200);
    List<Map<String, Object>> logLines = logLinesFor(capturedOutput, correlationId);

    Map<String, Object> toolExecutedData = eventData(singleEvent(logLines, "toolExecuted"));
    assertThat(toolExecutedData)
        .containsEntry("agentName", MarketDataAgentConfig.MARKET_DATA_AGENT_NAME)
        .containsEntry("toolName", "getQuote")
        .containsEntry("toolArguments", Map.of("symbol", "AAPL"))
        .containsEntry("toolFailed", false);
    assertThat(toolExecutedData.get("toolExecDurationTimeMs")).isInstanceOf(Number.class);

    Map<String, Object> answerProvenance = singleEvent(logLines, "answerProvenance");
    assertThat((String) answerProvenance.get("traceId")).matches(UUID_PATTERN);
    assertThat(eventData(answerProvenance))
        .containsEntry("agentName", SupervisorAgentConfig.SUPERVISOR_AGENT_NAME)
        .containsEntry("promptId", SupervisorAgentConfig.SUPERVISOR_PROMPT_ID)
        .containsEntry(
            "agentsUsed",
            List.of(
                Map.of(
                    "agentName",
                    MarketDataAgentConfig.MARKET_DATA_AGENT_NAME,
                    "promptId",
                    MarketDataAgent.PROMPT_ID,
                    "toolsUsed",
                    List.of("getQuote"))));
    assertThat(eventData(answerProvenance).get("completeExecDurationTimeMs"))
        .isInstanceOf(Number.class);
  }

  @Test
  void holdingsPerformanceProvenanceListsPortfolioAgentThenMarketDataAgent(
      CapturedOutput capturedOutput) throws Exception {
    String correlationId = "corr-" + UUID.randomUUID();

    HttpResponse<String> httpResponse =
        postChat(
            HOLDINGS_PERFORMANCE_PROMPT,
            Map.of("X-Correlation-Id", correlationId, "X-User-Id", "alice"));

    assertThat(httpResponse.statusCode()).isEqualTo(200);
    Map<String, Object> answerProvenance =
        singleEvent(logLinesFor(capturedOutput, correlationId), "answerProvenance");
    assertThat(eventData(answerProvenance))
        .containsEntry(
            "agentsUsed",
            List.of(
                Map.of(
                    "agentName",
                    PortfolioAgentConfig.PORTFOLIO_AGENT_NAME,
                    "promptId",
                    PortfolioAgent.PROMPT_ID,
                    "toolsUsed",
                    List.of("getHoldings")),
                Map.of(
                    "agentName",
                    MarketDataAgentConfig.MARKET_DATA_AGENT_NAME,
                    "promptId",
                    MarketDataAgent.PROMPT_ID,
                    "toolsUsed",
                    List.of("getQuote"))));
  }

  @Test
  void correlationIdIsForwardedToTheMcpServer() throws Exception {
    String correlationId = "corr-" + UUID.randomUUID();

    HttpResponse<String> httpResponse =
        postChat("price of Apple", Map.of("X-Correlation-Id", correlationId));

    assertThat(httpResponse.statusCode()).isEqualTo(200);
    assertThat(FAKE_MARKET_DATA_MCP.toolCallHeaders())
        .anySatisfy(
            toolCallHeaders -> {
              assertThat(toolCallHeaders).containsEntry("X-Correlation-Id", correlationId);
              assertThat(toolCallHeaders.get("X-Trace-Id")).matches(UUID_PATTERN);
            });
  }

  @Test
  void modelFailureIsLoggedOnceWithStackTraceAndClientGetsCleanError(CapturedOutput capturedOutput)
      throws Exception {
    String correlationId = "corr-" + UUID.randomUUID();

    HttpResponse<String> httpResponse =
        postChat(FAILING_PROMPT, Map.of("X-Correlation-Id", correlationId));

    assertThat(httpResponse.statusCode()).isEqualTo(500);
    assertThat(httpResponse.body())
        .contains("\"errorCode\":\"INTERNAL_ERROR\"")
        .doesNotContain(FORCED_FAILURE_MESSAGE);
    assertThat(
            capturedOutput.getOut().lines().filter(line -> line.contains(FORCED_FAILURE_MESSAGE)))
        .as("log lines mentioning the forced failure")
        .hasSize(1);
    Map<String, Object> exceptionLine =
        singleEvent(logLinesFor(capturedOutput, correlationId), "exceptionHandled");
    assertThat(exceptionLine).containsEntry("level", "ERROR").containsKey("stack_trace");
    assertThat(eventData(exceptionLine))
        .containsEntry("errorCode", "INTERNAL_ERROR")
        .containsEntry("httpStatus", 500)
        .containsEntry("exceptionType", IllegalStateException.class.getName())
        .containsEntry("exceptionMessage", FORCED_FAILURE_MESSAGE);
  }

  @Test
  void invalidRequestIsLoggedOnceWithoutStackTrace(CapturedOutput capturedOutput) throws Exception {
    String correlationId = "corr-" + UUID.randomUUID();

    HttpResponse<String> httpResponse = postChat(" ", Map.of("X-Correlation-Id", correlationId));

    assertThat(httpResponse.statusCode()).isEqualTo(400);
    assertThat(httpResponse.body()).contains("\"errorCode\":\"BAD_REQUEST\"");
    Map<String, Object> exceptionLine =
        singleEvent(logLinesFor(capturedOutput, correlationId), "exceptionHandled");
    assertThat(exceptionLine).containsEntry("level", "ERROR").doesNotContainKey("stack_trace");
    assertThat(eventData(exceptionLine))
        .containsEntry("errorCode", "BAD_REQUEST")
        .containsEntry("httpStatus", 400)
        .containsEntry("exceptionMessageSuppressed", true)
        .doesNotContainKey("exceptionMessage");
  }

  @Test
  void chatLogsClassificationAndSkippedCacheLookup(CapturedOutput capturedOutput) throws Exception {
    String correlationId = "corr-" + UUID.randomUUID();

    HttpResponse<String> httpResponse =
        postChat("Price of Apple", Map.of("X-Correlation-Id", correlationId));

    assertThat(httpResponse.statusCode()).isEqualTo(200);
    List<Map<String, Object>> logLines = logLinesFor(capturedOutput, correlationId);
    Map<String, Object> promptClassifiedData = eventData(singleEvent(logLines, "promptClassified"));
    assertThat(promptClassifiedData)
        .containsEntry("freshnessClass", "REAL_TIME")
        .containsEntry("tickers", List.of("AAPL"));
    assertThat(promptClassifiedData.get("classifyDurationTimeMs")).isInstanceOf(Number.class);
    assertThat(eventData(singleEvent(logLines, "semanticCacheLookup")))
        .containsEntry("cacheOutcome", "SKIPPED")
        .containsEntry("freshnessClass", "REAL_TIME")
        .containsEntry("tickers", List.of("AAPL"));
    assertThat(logLines)
        .noneMatch(logLine -> "semanticCacheSkipped".equals(logLine.get("eventName")));
  }

  @Test
  void answerProvenanceListsTheModelAndTokensOfTheClassifierAndEachAgentThatRan(
      CapturedOutput capturedOutput) throws Exception {
    String correlationId = "corr-" + UUID.randomUUID();

    HttpResponse<String> httpResponse =
        postChat("Price of Apple", Map.of("X-Correlation-Id", correlationId));

    assertThat(httpResponse.statusCode()).isEqualTo(200);
    Map<String, Object> provenanceData =
        eventData(singleEvent(logLinesFor(capturedOutput, correlationId), "answerProvenance"));
    assertThat(provenanceData)
        .containsEntry("cacheOutcome", "SKIPPED")
        .containsEntry(
            "modelByAgent",
            Map.of(
                PromptClassifierConfig.CLASSIFIER_NAME,
                ScriptedAgentsChatModel.FAKE_MODEL_NAME,
                SupervisorAgentConfig.SUPERVISOR_AGENT_NAME,
                ScriptedAgentsChatModel.FAKE_MODEL_NAME,
                MarketDataAgentConfig.MARKET_DATA_AGENT_NAME,
                ScriptedAgentsChatModel.FAKE_MODEL_NAME));
    assertThat(provenanceData.get("tokensByAgent"))
        .asInstanceOf(MAP)
        .containsEntry(
            PromptClassifierConfig.CLASSIFIER_NAME, Map.of("inputTokens", 100, "outputTokens", 10));
  }

  @Test
  void classifierFailureIsLoggedAsSkippedCacheAndTheSupervisorStillAnswers(
      CapturedOutput capturedOutput) throws Exception {
    String correlationId = "corr-" + UUID.randomUUID();

    HttpResponse<String> httpResponse =
        postChat(CLASSIFIER_FAILING_PROMPT, Map.of("X-Correlation-Id", correlationId));

    assertThat(httpResponse.statusCode()).isEqualTo(200);
    assertThat(
            jsonMapper.readValue(httpResponse.body(), ChatController.ChatResponse.class).answer())
        .contains("189.42");
    List<Map<String, Object>> logLines = logLinesFor(capturedOutput, correlationId);
    Map<String, Object> skippedLine = singleEvent(logLines, "semanticCacheSkipped");
    assertThat(skippedLine).containsEntry("level", "WARN");
    assertThat(eventData(skippedLine))
        .containsEntry("skipReason", "classifierFailed")
        .containsEntry("exceptionType", IllegalStateException.class.getName())
        .containsEntry("exceptionMessage", CLASSIFIER_FAILURE_MESSAGE);
    assertThat(eventData(singleEvent(logLines, "semanticCacheLookup")))
        .containsEntry("cacheOutcome", "SKIPPED")
        .containsEntry("freshnessClass", "REAL_TIME");
    Map<String, Object> provenanceData = eventData(singleEvent(logLines, "answerProvenance"));
    assertThat(provenanceData.get("modelByAgent"))
        .asInstanceOf(MAP)
        .doesNotContainKey(PromptClassifierConfig.CLASSIFIER_NAME);
  }

  @Test
  void invalidTickerIsLoggedAsSkippedCacheAndTheSupervisorStillAnswers(
      CapturedOutput capturedOutput) throws Exception {
    String correlationId = "corr-" + UUID.randomUUID();

    HttpResponse<String> httpResponse =
        postChat(INVALID_TICKER_PROMPT, Map.of("X-Correlation-Id", correlationId));

    assertThat(httpResponse.statusCode()).isEqualTo(200);
    List<Map<String, Object>> logLines = logLinesFor(capturedOutput, correlationId);
    Map<String, Object> skippedLine = singleEvent(logLines, "semanticCacheSkipped");
    assertThat(skippedLine).containsEntry("level", "WARN");
    assertThat(eventData(skippedLine))
        .containsEntry("skipReason", "invalidTicker")
        .containsEntry("invalidTickers", List.of("APPLE1"));
    assertThat(eventData(singleEvent(logLines, "semanticCacheLookup")))
        .containsEntry("cacheOutcome", "SKIPPED")
        .containsEntry("freshnessClass", "REAL_TIME")
        .containsEntry("tickers", List.of());
    assertThat(logLines).noneMatch(logLine -> "promptClassified".equals(logLine.get("eventName")));
  }
}
