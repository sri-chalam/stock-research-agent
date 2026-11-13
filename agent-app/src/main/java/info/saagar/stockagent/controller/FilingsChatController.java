package info.saagar.stockagent.controller;

import dev.langchain4j.service.Result;
import info.saagar.stockagent.agent.FilingsAgent;
import info.saagar.stockagent.config.FilingsAgentConfig;
import info.saagar.stockagent.controller.ChatController.ChatRequest;
import info.saagar.stockagent.controller.ChatController.ChatResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/// Synchronous chat endpoint backed by the Filings analyst.
///
/// Temporary: Iteration 3 removes it when the Supervisor routes filings questions from `/api/chat`.
@RestController
@RequestMapping("/api/filings/chat")
public class FilingsChatController {

  private final FilingsAgent filingsAgent;
  private final JsonMapper jsonMapper;

  /// Creates the controller.
  ///
  /// @param filingsAgent agent that answers prompts
  /// @param jsonMapper mapper used to parse tool-call arguments
  public FilingsChatController(FilingsAgent filingsAgent, JsonMapper jsonMapper) {
    this.filingsAgent = filingsAgent;
    this.jsonMapper = jsonMapper;
  }

  /// Answers a prompt with the Filings analyst.
  ///
  /// @param chatRequest the user's prompt
  /// @return the answer and the tools called
  @PostMapping
  public ChatResponse chat(@Valid @RequestBody ChatRequest chatRequest) {
    long startNanos = System.nanoTime();
    Result<String> agentResult = filingsAgent.answer(chatRequest.message());
    return ChatController.toChatResponse(
        jsonMapper,
        FilingsAgentConfig.FILINGS_AGENT_NAME,
        FilingsAgent.PROMPT_ID,
        agentResult,
        startNanos);
  }
}
