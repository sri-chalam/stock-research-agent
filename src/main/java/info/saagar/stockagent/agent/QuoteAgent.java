package info.saagar.stockagent.agent;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;

/// Answers quick price questions by calling the `getQuote` tool.
public interface QuoteAgent {

  /// ID of the system prompt version, taken from its filename and logged with every answer.
  String PROMPT_ID = "quote-agent-v1";

  /// Answers a user's question about a stock price.
  ///
  /// @param userMessage the user's question, e.g. "price of Apple"
  /// @return the answer text plus the tool executions that produced it
  @SystemMessage(fromResource = "prompts/" + PROMPT_ID + ".txt")
  Result<String> answer(String userMessage);
}
