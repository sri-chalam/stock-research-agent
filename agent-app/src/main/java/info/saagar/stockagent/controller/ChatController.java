package info.saagar.stockagent.controller;

import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.agentic.supervisor.SupervisorAgent;
import dev.langchain4j.service.tool.ToolExecution;
import info.saagar.stockagent.agent.FilingsAgent;
import info.saagar.stockagent.agent.MarketDataAgent;
import info.saagar.stockagent.agent.PortfolioAgent;
import info.saagar.stockagent.cache.CacheLookup;
import info.saagar.stockagent.cache.SemanticCache;
import info.saagar.stockagent.classifier.ClassifiedPrompt;
import info.saagar.stockagent.classifier.PromptClassifier;
import info.saagar.stockagent.config.FilingsAgentConfig;
import info.saagar.stockagent.config.MarketDataAgentConfig;
import info.saagar.stockagent.config.PortfolioAgentConfig;
import info.saagar.stockagent.config.PromptClassifierConfig;
import info.saagar.stockagent.config.SupervisorAgentConfig;
import info.saagar.stockagent.logging.AgentProvenanceListener;
import info.saagar.stockagent.logging.RunProvenance;
import info.saagar.stockagent.logging.RunProvenance.TokenCount;
import info.saagar.stockagent.tool.McpUserHeaders;
import info.saagar.stockagent.tool.ToolArguments;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/// Synchronous chat endpoint: classifies each prompt, answers it from the semantic cache when a
/// similar enough question was answered before, and otherwise with the Supervisor, which delegates
/// to the specialist agents.
@RestController
@RequestMapping("/api/chat")
public class ChatController {

  private static final Logger log = LoggerFactory.getLogger(ChatController.class);

  /// Prompt version of each specialist agent, logged per agent call (AG-1).
  private static final Map<String, String> PROMPT_ID_BY_AGENT_NAME =
      Map.of(
          FilingsAgentConfig.FILINGS_AGENT_NAME, FilingsAgent.PROMPT_ID,
          MarketDataAgentConfig.MARKET_DATA_AGENT_NAME, MarketDataAgent.PROMPT_ID,
          PortfolioAgentConfig.PORTFOLIO_AGENT_NAME, PortfolioAgent.PROMPT_ID);

  /// Accepted `X-User-Id` values; any other value is rejected with 400.
  private static final String USER_ID_PATTERN = "^[A-Za-z0-9-]{1,64}$";

  private final PromptClassifier promptClassifier;
  private final SemanticCache semanticCache;
  private final SupervisorAgent supervisorAgent;
  private final AgentProvenanceListener agentProvenanceListener;
  private final JsonMapper jsonMapper;

  /// Creates the controller.
  ///
  /// @param promptClassifier classifies each prompt's freshness and tickers before the Supervisor
  /// @param semanticCache answers repeated questions without running agents, and stores answers
  /// @param supervisorAgent agent that answers prompts through the specialist agents
  /// @param agentProvenanceListener records which specialists, tools and tokens each run used
  /// @param jsonMapper mapper used to parse tool-call arguments
  public ChatController(
      PromptClassifier promptClassifier,
      SemanticCache semanticCache,
      SupervisorAgent supervisorAgent,
      AgentProvenanceListener agentProvenanceListener,
      JsonMapper jsonMapper) {
    this.promptClassifier = promptClassifier;
    this.semanticCache = semanticCache;
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
  /// @param toolsCalled tools called, in order; empty for a cached answer
  /// @param cached `true` when the answer came from the semantic cache and no agents ran
  public record ChatResponse(String answer, List<ToolCall> toolsCalled, boolean cached) {}

  /// Classifies a prompt, then answers it from the semantic cache or with the Supervisor.
  ///
  /// The user ID goes into the MDC for the run, and from there only onto MCP calls to
  /// `portfolio-mcp` ([McpUserHeaders]); it never enters the prompt (SEC-4).
  ///
  /// @param chatRequest the user's prompt
  /// @param userId the `X-User-Id` header, or `null` when absent; without it, portfolio questions
  ///   get the portfolio tools' "No user identified" error
  /// @return the answer and the tools called
  @PostMapping
  public ChatResponse chat(
      @Valid @RequestBody ChatRequest chatRequest,
      @RequestHeader(name = McpUserHeaders.USER_ID_HEADER, required = false)
          @Pattern(regexp = USER_ID_PATTERN)
          String userId) {
    try (MDC.MDCCloseable userContext =
        userId == null ? null : MDC.putCloseable(McpUserHeaders.USER_ID_MDC_KEY, userId)) {
      return answer(chatRequest.message());
    }
  }

  private ChatResponse answer(String message) {
    long startNanos = System.nanoTime();
    ClassifiedPrompt classifiedPrompt = promptClassifier.classify(message);
    CacheLookup cacheLookup = semanticCache.lookup(message, classifiedPrompt);
    if (cacheLookup instanceof CacheLookup.Hit cacheHit) {
      RunProvenance classifierOnlyProvenance = new RunProvenance();
      addClassifierResponse(classifierOnlyProvenance, classifiedPrompt);
      logAnswerProvenance(
          classifierOnlyProvenance,
          cacheLookup,
          cacheHit.cachedAnswer().runTotalTokens(),
          startNanos);
      return new ChatResponse(cacheHit.cachedAnswer().answer(), List.of(), true);
    }
    ResultWithAgenticScope<String> supervisorResult =
        supervisorAgent.invokeWithAgenticScope(message);
    RunProvenance runProvenance =
        agentProvenanceListener.takeRunProvenance(supervisorResult.agenticScope());
    addClassifierResponse(runProvenance, classifiedPrompt);
    semanticCache.store(cacheLookup, supervisorResult.result(), runProvenance);
    List<ToolCall> toolsCalled =
        runProvenance.agentCalls().stream()
            .flatMap(
                agentCall ->
                    agentCall.toolExecutions().stream()
                        .map(toolExecution -> toToolCall(agentCall.agentName(), toolExecution)))
            .toList();
    logAnswerProvenance(runProvenance, cacheLookup, null, startNanos);
    return new ChatResponse(supervisorResult.result(), toolsCalled, false);
  }

  private static void addClassifierResponse(
      RunProvenance runProvenance, ClassifiedPrompt classifiedPrompt) {
    if (classifiedPrompt.classifierResponse() != null) {
      runProvenance.modelResponded(
          PromptClassifierConfig.CLASSIFIER_NAME, classifiedPrompt.classifierResponse());
    }
  }

  private ToolCall toToolCall(String agentName, ToolExecution toolExecution) {
    return new ToolCall(
        agentName,
        toolExecution.request().name(),
        ToolArguments.parse(jsonMapper, toolExecution.request().arguments()));
  }

  /// Logs the one `answerProvenance` line that says which agents, prompts and tools made the
  /// answer, which model answered each agent and the classifier, how many tokens each used, and the
  /// cache outcome. On a hit no agents ran, so only the classifier's tokens are listed, and
  /// `tokensSaved` is the stored run's total.
  private static void logAnswerProvenance(
      RunProvenance runProvenance, CacheLookup cacheLookup, Long tokensSaved, long startNanos) {
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
    provenanceData.put("cacheOutcome", cacheLookup.cacheOutcome());
    if (tokensSaved != null) {
      provenanceData.put("tokensSaved", tokensSaved);
    }
    provenanceData.put("modelByAgent", runProvenance.modelByAgent());
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
