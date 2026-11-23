package info.saagar.stockagent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import info.saagar.stockagent.common.testing.TestJwts;
import info.saagar.stockagent.testing.FakeMcpServer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
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

/// Verifies the agent and tool spans of a `/api/chat` run, through an in-memory OpenTelemetry SDK
/// instead of the Java agent.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"ANTHROPIC_API_KEY=test-key", "stock-agent.semantic-cache.store=in-memory"})
@Import(ChatTracingTest.TracingTestConfig.class)
class ChatTracingTest {

  private static final FakeMcpServer FAKE_MARKET_DATA_MCP = FakeMcpServer.start("getQuote");
  private static final FakeMcpServer FAKE_FILINGS_MCP = FakeMcpServer.start("listFilings");
  private static final FakeMcpServer FAKE_PORTFOLIO_MCP =
      FakeMcpServer.start("getHoldings", "getWatchlist");

  private static final InMemorySpanExporter SPAN_EXPORTER = InMemorySpanExporter.create();
  private static final Tracer TEST_TRACER =
      SdkTracerProvider.builder()
          .addSpanProcessor(SimpleSpanProcessor.create(SPAN_EXPORTER))
          .build()
          .get("chat-tracing-test");

  private static final String USER_1_TOKEN = TestJwts.token("user_test_1", "agent.chat");
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
  private static final String NO_PARENT = "none";

  @DynamicPropertySource
  static void pointAgentAtFakeMcpServer(DynamicPropertyRegistry propertyRegistry) {
    propertyRegistry.add("stock-agent.mcp-servers.market-data.url", FAKE_MARKET_DATA_MCP::url);
    propertyRegistry.add("stock-agent.mcp-servers.filings.url", FAKE_FILINGS_MCP::url);
    propertyRegistry.add("stock-agent.mcp-servers.portfolio.url", FAKE_PORTFOLIO_MCP::url);
    TestJwts.registerResourceServerProperties(propertyRegistry);
  }

  @TestConfiguration
  static class TracingTestConfig {
    @Bean
    @Primary
    Tracer testTracer() {
      return TEST_TRACER;
    }

    @Bean
    @Primary
    ChatModel spanRecordingChatModel() {
      return new SpanRecordingChatModel(
          ScriptedAgentsChatModel.portfolioThenMarketData("AAPL"), TEST_TRACER);
    }
  }

  /// Plays every agent with a scripted model and wraps each call in a span named `llmCall` plus the
  /// calling agent, as the Java agent's HTTP client span wraps a real LLM call: its parent is
  /// whatever span is current on the calling thread.
  private static final class SpanRecordingChatModel implements ChatModel {

    private final ChatModel scriptedModel;
    private final Tracer tracer;

    private SpanRecordingChatModel(ChatModel scriptedModel, Tracer tracer) {
      this.scriptedModel = scriptedModel;
      this.tracer = tracer;
    }

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
      Span llmCallSpan =
          tracer
              .spanBuilder("llmCall " + ScriptedAgentsChatModel.agentNameFor(chatRequest))
              .startSpan();
      try (Scope llmCallScope = llmCallSpan.makeCurrent()) {
        return scriptedModel.doChat(chatRequest);
      } finally {
        llmCallSpan.end();
      }
    }
  }

  @LocalServerPort private int port;
  @Autowired private JsonMapper jsonMapper;

  @BeforeEach
  void clearSpans() {
    SPAN_EXPORTER.reset();
  }

  @Test
  void eachAgentSpanNestsItsLlmAndToolCallsAndTheSpecialistsNestUnderTheSupervisor()
      throws Exception {
    postChat("How are my holdings doing today?");

    assertThat(spanNamesWithParentNames())
        .containsOnly(
            tuple("llmCall classifier", NO_PARENT),
            tuple("supervisor", NO_PARENT),
            tuple("llmCall supervisor", "supervisor"),
            tuple("portfolio", "supervisor"),
            tuple("llmCall portfolio", "portfolio"),
            tuple("getHoldings", "portfolio"),
            tuple("market-data", "supervisor"),
            tuple("llmCall market-data", "market-data"),
            tuple("getQuote", "market-data"));
    assertThat(SPAN_EXPORTER.getFinishedSpanItems())
        .extracting(SpanData::getName)
        .filteredOn(spanName -> !spanName.startsWith("llmCall"))
        .containsExactlyInAnyOrder(
            "supervisor", "portfolio", "getHoldings", "market-data", "getQuote");
  }

  private void postChat(String message) throws Exception {
    HttpRequest chatRequest =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/chat"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + USER_1_TOKEN)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    jsonMapper.writeValueAsString(new ChatController.ChatRequest(message))))
            .build();
    HttpResponse<String> chatResponse =
        HTTP_CLIENT.send(chatRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(chatResponse.statusCode()).isEqualTo(200);
  }

  /// Each finished span's name with its parent's name, de-duplicated; [#NO_PARENT] for a root.
  private static List<Tuple> spanNamesWithParentNames() {
    List<SpanData> finishedSpans = SPAN_EXPORTER.getFinishedSpanItems();
    Map<String, String> spanNameBySpanId =
        finishedSpans.stream().collect(Collectors.toMap(SpanData::getSpanId, SpanData::getName));
    return finishedSpans.stream()
        .map(
            spanData ->
                tuple(
                    spanData.getName(),
                    spanNameBySpanId.getOrDefault(spanData.getParentSpanId(), NO_PARENT)))
        .distinct()
        .toList();
  }
}
