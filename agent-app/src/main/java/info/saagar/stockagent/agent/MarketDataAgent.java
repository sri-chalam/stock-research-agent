package info.saagar.stockagent.agent;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import info.saagar.stockagent.config.MarketDataAgentConfig;

/// Answers market-data questions (quotes, company profiles, news, earnings dates and key metrics)
/// with the `market-data-mcp` tools on its allowlist. The Supervisor calls it with a sub-question.
public interface MarketDataAgent {

  /// ID of the system prompt version, taken from its filename and logged with every call.
  String PROMPT_ID = "market-data-agent-v3";

  /// Answers a market-data sub-question.
  ///
  /// @param request the Supervisor's sub-question, e.g. "Current price of Apple (AAPL)"
  /// @return the answer text
  @Agent(
      name = MarketDataAgentConfig.MARKET_DATA_AGENT_NAME,
      description =
          "Answers questions about a US-listed stock's current price quote, company profile,"
              + " company and market news, earnings dates and current key financial metrics.")
  @SystemMessage(fromResource = "prompts/" + PROMPT_ID + ".txt")
  @UserMessage("{{request}}")
  String answer(@V("request") String request);
}
