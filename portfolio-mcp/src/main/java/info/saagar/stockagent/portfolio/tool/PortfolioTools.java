package info.saagar.stockagent.portfolio.tool;

import dev.langchain4j.agent.tool.Tool;
import info.saagar.stockagent.portfolio.repository.Holding;
import info.saagar.stockagent.portfolio.repository.PortfolioRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/// Portfolio tools served over MCP. Both are read-only and take no arguments: the user comes from
/// [PortfolioUser#USER_ID].
///
/// Errors are thrown, never returned: the MCP server turns a thrown exception into a tool result
/// with `isError: true` and the exception message as its text.
@Component
public class PortfolioTools {

  private static final Logger log = LoggerFactory.getLogger(PortfolioTools.class);

  private static final String PORTFOLIO_UNAVAILABLE_MESSAGE =
      "The portfolio is unavailable; try again shortly.";

  private final PortfolioRepository portfolioRepository;

  /// Creates the tools.
  ///
  /// @param portfolioRepository source of holdings and watchlists
  public PortfolioTools(PortfolioRepository portfolioRepository) {
    this.portfolioRepository = portfolioRepository;
  }

  /// Returns the current user's holdings.
  ///
  /// @return ticker and shares per holding, ordered by ticker; empty when the user holds nothing
  /// @throws IllegalStateException when the store fails
  @Tool(
      "Get the current user's paper-portfolio holdings: ticker and number of shares for each"
          + " position, ordered by ticker. Takes no arguments; the user is identified by the"
          + " request. An empty list means the user holds nothing.")
  public List<Holding> getHoldings() {
    return readForCurrentUser("getHoldings", portfolioRepository::findHoldings);
  }

  /// Returns the tickers on the current user's watchlist.
  ///
  /// @return tickers in alphabetical order; empty when the watchlist is empty
  /// @throws IllegalStateException when the store fails
  @Tool(
      "Get the tickers on the current user's watchlist, in alphabetical order. Takes no"
          + " arguments; the user is identified by the request. An empty list means the"
          + " watchlist is empty.")
  public List<String> getWatchlist() {
    return readForCurrentUser("getWatchlist", portfolioRepository::findWatchlistTickers);
  }

  private static <T> List<T> readForCurrentUser(
      String toolName, Function<String, List<T>> userScopedRead) {
    long startNanos = System.nanoTime();
    try {
      List<T> userRows = userScopedRead.apply(PortfolioUser.USER_ID.get());
      log.atInfo()
          .addKeyValue("eventName", "portfolioRowsRead")
          .addKeyValue(
              "eventData",
              Map.of(
                  "toolName",
                  toolName,
                  "rowCount",
                  userRows.size(),
                  "queryDurationTimeMs",
                  (System.nanoTime() - startNanos) / 1_000_000))
          .log("Portfolio rows read");
      return userRows;
    } catch (DataAccessException dataAccessException) {
      log.atError()
          .addKeyValue("eventName", "portfolioQueryFailed")
          .addKeyValue(
              "eventData",
              Map.of(
                  "toolName",
                  toolName,
                  "exceptionType",
                  dataAccessException.getClass().getName(),
                  "exceptionMessageSuppressed",
                  true))
          .log("Portfolio query failed");
      throw new IllegalStateException(PORTFOLIO_UNAVAILABLE_MESSAGE);
    }
  }
}
