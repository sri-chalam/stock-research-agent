package info.saagar.stockagent.agent;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;

/// The Filings analyst: answers SEC filings questions with the `filings-mcp` tools on its
/// allowlist.
public interface FilingsAgent {

  /// ID of the system prompt version, taken from its filename and logged with every answer.
  String PROMPT_ID = "filings-agent-v2";

  /// Answers a user's filings question.
  ///
  /// @param userMessage the user's question, e.g. "List Microsoft's recent 10-K filings"
  /// @return the answer text plus the tool executions that produced it
  @SystemMessage(fromResource = "prompts/" + PROMPT_ID + ".txt")
  Result<String> answer(String userMessage);
}
