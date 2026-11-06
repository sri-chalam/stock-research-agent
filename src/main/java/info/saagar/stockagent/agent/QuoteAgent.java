package info.saagar.stockagent.agent;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;

/// Answers quick price questions by calling the `getQuote` tool.
public interface QuoteAgent {

  /// Answers a user's question about a stock price.
  ///
  /// @param userMessage the user's question, e.g. "price of Apple"
  /// @return the answer text plus the tool executions that produced it
  @SystemMessage(fromResource = "prompts/quote-agent-v1.txt")
  Result<String> answer(String userMessage);
}
