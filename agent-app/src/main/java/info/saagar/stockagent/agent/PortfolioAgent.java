package info.saagar.stockagent.agent;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import info.saagar.stockagent.config.PortfolioAgentConfig;

/// The Portfolio agent: reads the current user's holdings and watchlist with the `portfolio-mcp`
/// tools on its allowlist. The Supervisor calls it with a sub-question. The user is identified by
/// the `X-User-Id` header on the MCP call, never by the sub-question.
public interface PortfolioAgent {

  /// ID of the system prompt version, taken from its filename and logged with every call.
  String PROMPT_ID = "portfolio-agent-v1";

  /// Answers a portfolio sub-question.
  ///
  /// @param request the Supervisor's sub-question, e.g. "Which stocks does the user hold?"
  /// @return the answer text
  @Agent(
      name = PortfolioAgentConfig.PORTFOLIO_AGENT_NAME,
      description =
          "Reads the current user's paper portfolio: the tickers and share counts they hold, and"
              + " the tickers on their watchlist. Knows who the user is. Has no prices or news.")
  @SystemMessage(fromResource = "prompts/" + PROMPT_ID + ".txt")
  @UserMessage("{{request}}")
  String answer(@V("request") String request);
}
