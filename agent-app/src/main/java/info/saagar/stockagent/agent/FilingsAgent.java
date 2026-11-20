package info.saagar.stockagent.agent;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import info.saagar.stockagent.config.FilingsAgentConfig;

/// The Filings analyst: answers SEC filings questions with the `filings-mcp` tools on its
/// allowlist. The Supervisor calls it with a sub-question.
public interface FilingsAgent {

  /// ID of the system prompt version, taken from its filename and logged with every call.
  String PROMPT_ID = "filings-agent-v3";

  /// Answers a filings sub-question.
  ///
  /// @param request the Supervisor's sub-question, e.g. "List Microsoft's recent 10-K filings"
  /// @return the answer text
  @Agent(
      name = FilingsAgentConfig.FILINGS_AGENT_NAME,
      description =
          "Answers questions about a US company's SEC filings (10-K, 10-Q, 8-K): lists filings,"
              + " reports revenue, net income, gross profit, diluted EPS, total assets and cash for"
              + " the last 3 fiscal years, and searches what a filing says, e.g. its risk factors.")
  @SystemMessage(fromResource = "prompts/" + PROMPT_ID + ".txt")
  @UserMessage("{{request}}")
  String answer(@V("request") String request);
}
