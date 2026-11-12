package info.saagar.stockagent.agent;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;

/// Answers market-data questions (quotes, company profiles, news, earnings dates and key metrics)
/// with the `market-data-mcp` tools on its allowlist.
public interface MarketDataAgent {

  /// ID of the system prompt version, taken from its filename and logged with every answer.
  String PROMPT_ID = "market-data-agent-v1";

  /// Answers a user's market-data question.
  ///
  /// @param userMessage the user's question, e.g. "price of Apple"
  /// @return the answer text plus the tool executions that produced it
  @SystemMessage(fromResource = "prompts/" + PROMPT_ID + ".txt")
  Result<String> answer(String userMessage);
}
