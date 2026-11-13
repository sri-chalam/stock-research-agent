package info.saagar.stockagent.controller;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.model.chat.ChatModel;
import info.saagar.stockagent.agent.MarketDataAgent;
import info.saagar.stockagent.config.MarketDataAgentConfig;
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

  @DynamicPropertySource
  static void pointAgentAtFakeMcpServer(DynamicPropertyRegistry propertyRegistry) {
    propertyRegistry.add("stock-agent.mcp-servers.market-data.url", FAKE_MARKET_DATA_MCP::url);
    propertyRegistry.add("stock-agent.mcp-servers.filings.url", FAKE_FILINGS_MCP::url);
  }

  private static final String FAILING_PROMPT = "please fail";
  private static final String FORCED_FAILURE_MESSAGE = "Forced model failure for logging test";
  private static final String UUID_PATTERN =
      "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
  private static final TypeReference<Map<String, Object>> LOG_LINE_TYPE = new TypeReference<>() {};
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  @TestConfiguration
  static class FakeModelConfig {
    @Bean
    @Primary
    ChatModel failableQuoteChatModel() {
      return new FailableQuoteChatModel();
    }
  }

  /// Throws for the failing prompt; otherwise asks for `getQuote(AAPL)` and relays the result.
  static class FailableQuoteChatModel implements ChatModel {
    @Override
    public dev.langchain4j.model.chat.response.ChatResponse doChat(
        dev.langchain4j.model.chat.request.ChatRequest chatRequest) {
      boolean asksToFail =
          chatRequest.messages().stream()
              .anyMatch(message -> message.toString().contains(FAILING_PROMPT));
      if (asksToFail) {
        throw new IllegalStateException(FORCED_FAILURE_MESSAGE);
      }
      return new ScriptedQuoteChatModel("AAPL").doChat(chatRequest);
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
        .containsEntry("toolName", "getQuote")
        .containsEntry("toolArguments", Map.of("symbol", "AAPL"))
        .containsEntry("toolFailed", false);
    assertThat(toolExecutedData.get("toolExecDurationTimeMs")).isInstanceOf(Number.class);

    Map<String, Object> answerProvenance = singleEvent(logLines, "answerProvenance");
    assertThat((String) answerProvenance.get("traceId")).matches(UUID_PATTERN);
    assertThat(eventData(answerProvenance))
        .containsEntry("agentName", MarketDataAgentConfig.MARKET_DATA_AGENT_NAME)
        .containsEntry("promptId", MarketDataAgent.PROMPT_ID)
        .containsEntry("toolsUsed", List.of("getQuote"))
        .containsEntry("inputTokenCount", 20)
        .containsEntry("outputTokenCount", 10)
        .containsEntry("totalTokenCount", 30);
    assertThat(eventData(answerProvenance).get("completeExecDurationTimeMs"))
        .isInstanceOf(Number.class);
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
}
