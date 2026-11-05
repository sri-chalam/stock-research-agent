package info.saagar.stockagent.controller;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.tool.ToolExecution;
import info.saagar.stockagent.agent.QuoteAgent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/// Synchronous chat endpoint backed by the Quote agent.
@RestController
@RequestMapping("/api/chat")
public class ChatController {

  private static final Logger log = LoggerFactory.getLogger(ChatController.class);
  private static final TypeReference<Map<String, Object>> ARGUMENTS_TYPE = new TypeReference<>() {};

  private final QuoteAgent quoteAgent;
  private final JsonMapper jsonMapper;

  public ChatController(QuoteAgent quoteAgent, JsonMapper jsonMapper) {
    this.quoteAgent = quoteAgent;
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

  /// Answers a prompt with the Quote agent.
  ///
  /// @param chatRequest the user's prompt
  /// @return the answer and the tools called
  @PostMapping
  public ChatResponse chat(@Valid @RequestBody ChatRequest chatRequest) {
    long startNanos = System.nanoTime();
    Result<String> agentResult = quoteAgent.answer(chatRequest.message());
    List<ToolCall> toolsCalled =
        agentResult.toolExecutions().stream().map(this::toToolCall).toList();
    log.atInfo()
        .addKeyValue("eventName", "chatCompleted")
        .addKeyValue(
            "eventData",
            Map.of(
                "toolCallCount",
                toolsCalled.size(),
                "completeExecDurationTimeMs",
                (System.nanoTime() - startNanos) / 1_000_000))
        .log("Chat request completed");
    return new ChatResponse(agentResult.content(), toolsCalled);
  }

  private ToolCall toToolCall(ToolExecution toolExecution) {
    Map<String, Object> toolArguments =
        jsonMapper.readValue(toolExecution.request().arguments(), ARGUMENTS_TYPE);
    return new ToolCall(toolExecution.request().name(), toolArguments);
  }
}
