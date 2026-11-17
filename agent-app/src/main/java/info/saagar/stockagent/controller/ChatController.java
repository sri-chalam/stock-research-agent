package info.saagar.stockagent.controller;

import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.agentic.supervisor.SupervisorAgent;
import dev.langchain4j.service.tool.ToolExecution;
import info.saagar.stockagent.agent.FilingsAgent;
import info.saagar.stockagent.agent.MarketDataAgent;
import info.saagar.stockagent.config.FilingsAgentConfig;
import info.saagar.stockagent.config.MarketDataAgentConfig;
import info.saagar.stockagent.config.SupervisorAgentConfig;
import info.saagar.stockagent.logging.AgentProvenanceListener;
import info.saagar.stockagent.logging.RunProvenance;
import info.saagar.stockagent.logging.RunProvenance.TokenCount;
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

/// Synchronous chat endpoint backed by the Supervisor, which delegates to the specialist agents.
@RestController
@RequestMapping("/api/chat")
public class ChatController {

  private static final Logger log = LoggerFactory.getLogger(ChatController.class);

  /// Prompt version of each specialist agent, logged per agent call (AG-1).
  private static final Map<String, String> PROMPT_ID_BY_AGENT_NAME =
      Map.of(
          FilingsAgentConfig.FILINGS_AGENT_NAME, FilingsAgent.PROMPT_ID,
          MarketDataAgentConfig.MARKET_DATA_AGENT_NAME, MarketDataAgent.PROMPT_ID);

  private final SupervisorAgent supervisorAgent;
  private final AgentProvenanceListener agentProvenanceListener;
  private final JsonMapper jsonMapper;

  /// Creates the controller.
  ///
  /// @param supervisorAgent agent that answers prompts through the specialist agents
  /// @param agentProvenanceListener records which specialists, tools and tokens each run used
  /// @param jsonMapper mapper used to parse tool-call arguments
  public ChatController(
      SupervisorAgent supervisorAgent,
      AgentProvenanceListener agentProvenanceListener,
      JsonMapper jsonMapper) {
    this.supervisorAgent = supervisorAgent;
    this.agentProvenanceListener = agentProvenanceListener;
    this.jsonMapper = jsonMapper;
  }

  /// A user's chat prompt.
  ///
  /// @param message the question to answer
  public record ChatRequest(@NotBlank String message) {}

  /// A tool a specialist agent called while answering.
  ///
  /// @param agentName the specialist agent that made the call, e.g. `market-data`
  /// @param name tool name
  /// @param arguments arguments the model passed to the tool
  public record ToolCall(String agentName, String name, Map<String, Object> arguments) {}

  /// The answer and the tools that produced it.
  ///
  /// @param answer answer text
  /// @param toolsCalled tools called, in order
  public record ChatResponse(String answer, List<ToolCall> toolsCalled) {}

  /// Answers a prompt with the Supervisor.
  ///
  /// @param chatRequest the user's prompt
  /// @return the answer and the tools called
  @PostMapping
  public ChatResponse chat(@Valid @RequestBody ChatRequest chatRequest) {
    long startNanos = System.nanoTime();
    ResultWithAgenticScope<String> supervisorResult =
        supervisorAgent.invokeWithAgenticScope(chatRequest.message());
    RunProvenance runProvenance =
        agentProvenanceListener.takeRunProvenance(supervisorResult.agenticScope());
    List<ToolCall> toolsCalled =
        runProvenance.agentCalls().stream()
            .flatMap(
                agentCall ->
                    agentCall.toolExecutions().stream()
                        .map(toolExecution -> toToolCall(agentCall.agentName(), toolExecution)))
            .toList();
    logAnswerProvenance(runProvenance, startNanos);
    return new ChatResponse(supervisorResult.result(), toolsCalled);
  }

  private ToolCall toToolCall(String agentName, ToolExecution toolExecution) {
    return new ToolCall(
        agentName,
        toolExecution.request().name(),
        ToolArguments.parse(jsonMapper, toolExecution.request().arguments()));
  }

  /// Logs the one `answerProvenance` line that says which agents, prompts and tools made the answer
  /// and how many tokens each agent used.
  private static void logAnswerProvenance(RunProvenance runProvenance, long startNanos) {
    List<Map<String, Object>> agentsUsed =
        runProvenance.agentCalls().stream()
            .map(
                agentCall ->
                    Map.<String, Object>of(
                        "agentName",
                        agentCall.agentName(),
                        "promptId",
                        PROMPT_ID_BY_AGENT_NAME.get(agentCall.agentName()),
                        "toolsUsed",
                        agentCall.toolExecutions().stream()
                            .map(toolExecution -> toolExecution.request().name())
                            .toList()))
            .toList();
    Map<String, Object> tokensByAgent = new LinkedHashMap<>();
    runProvenance
        .tokensByAgent()
        .forEach((agentName, tokenCount) -> tokensByAgent.put(agentName, toLogData(tokenCount)));
    TokenCount totalTokens = runProvenance.totalTokens();
    Map<String, Object> provenanceData = new LinkedHashMap<>();
    provenanceData.put("agentName", SupervisorAgentConfig.SUPERVISOR_AGENT_NAME);
    provenanceData.put("promptId", SupervisorAgentConfig.SUPERVISOR_PROMPT_ID);
    provenanceData.put("agentsUsed", agentsUsed);
    provenanceData.put("tokensByAgent", tokensByAgent);
    provenanceData.put("totalInputTokens", totalTokens.inputTokens());
    provenanceData.put("totalOutputTokens", totalTokens.outputTokens());
    provenanceData.put("completeExecDurationTimeMs", (System.nanoTime() - startNanos) / 1_000_000);
    log.atInfo()
        .addKeyValue("eventName", "answerProvenance")
        .addKeyValue("eventData", provenanceData)
        .log("Answer provenance");
  }

  private static Map<String, Object> toLogData(TokenCount tokenCount) {
    return Map.of(
        "inputTokens", tokenCount.inputTokens(), "outputTokens", tokenCount.outputTokens());
  }
}
