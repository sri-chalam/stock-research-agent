package info.saagar.stockagent.controller;

import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.tool.ToolExecution;
import info.saagar.stockagent.agent.MarketDataAgent;
import info.saagar.stockagent.config.MarketDataAgentConfig;
import info.saagar.stockagent.tool.ToolArguments;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/// Synchronous chat endpoint backed by the Market data agent.
@RestController
@RequestMapping("/api/chat")
public class ChatController {

  private static final Logger log = LoggerFactory.getLogger(ChatController.class);

  private final MarketDataAgent marketDataAgent;
  private final JsonMapper jsonMapper;

  /// Creates the controller.
  ///
  /// @param marketDataAgent agent that answers prompts
  /// @param jsonMapper mapper used to parse tool-call arguments
  public ChatController(MarketDataAgent marketDataAgent, JsonMapper jsonMapper) {
    this.marketDataAgent = marketDataAgent;
    this.jsonMapper = jsonMapper;
  }

  /// A user's chat prompt.
  ///
  /// @param message the question to answer
  public record ChatRequest(@NotBlank String message) {}

  /// A tool the agent called while answering.
  ///
  /// @param name tool name
  /// @param arguments arguments the model passed to the tool
  public record ToolCall(String name, Map<String, Object> arguments) {}

  /// The agent's answer and the tools that produced it.
  ///
  /// @param answer answer text
  /// @param toolsCalled tools called, in order
  public record ChatResponse(String answer, List<ToolCall> toolsCalled) {}

  /// Answers a prompt with the Market data agent.
  ///
  /// @param chatRequest the user's prompt
  /// @return the answer and the tools called
  @PostMapping
  public ChatResponse chat(@Valid @RequestBody ChatRequest chatRequest) {
    long startNanos = System.nanoTime();
    Result<String> agentResult = marketDataAgent.answer(chatRequest.message());
    List<ToolCall> toolsCalled =
        agentResult.toolExecutions().stream().map(this::toToolCall).toList();
    logAnswerProvenance(agentResult, toolsCalled, startNanos);
    return new ChatResponse(agentResult.content(), toolsCalled);
  }

  private ToolCall toToolCall(ToolExecution toolExecution) {
    return new ToolCall(
        toolExecution.request().name(),
        ToolArguments.parse(jsonMapper, toolExecution.request().arguments()));
  }

  /// Logs the one `answerProvenance` line that says which agent, prompt and tools made the answer.
  private static void logAnswerProvenance(
      Result<String> agentResult, List<ToolCall> toolsCalled, long startNanos) {
    TokenUsage tokenUsage = agentResult.tokenUsage();
    Map<String, Object> provenanceData = new LinkedHashMap<>();
    provenanceData.put("agentName", MarketDataAgentConfig.MARKET_DATA_AGENT_NAME);
    provenanceData.put("promptId", MarketDataAgent.PROMPT_ID);
    provenanceData.put("toolsUsed", toolsCalled.stream().map(ToolCall::name).toList());
    provenanceData.put("inputTokenCount", tokenUsage == null ? null : tokenUsage.inputTokenCount());
    provenanceData.put(
        "outputTokenCount", tokenUsage == null ? null : tokenUsage.outputTokenCount());
    provenanceData.put("totalTokenCount", tokenUsage == null ? null : tokenUsage.totalTokenCount());
    provenanceData.put("completeExecDurationTimeMs", (System.nanoTime() - startNanos) / 1_000_000);
    log.atInfo()
        .addKeyValue("eventName", "answerProvenance")
        .addKeyValue("eventData", provenanceData)
        .log("Answer provenance");
  }
}
