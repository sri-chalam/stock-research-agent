package info.saagar.stockagent.marketdata.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import info.saagar.stockagent.marketdata.provider.FinnhubClient;
import info.saagar.stockagent.marketdata.provider.MarketDataException;
import info.saagar.stockagent.marketdata.provider.Quote;
import org.springframework.stereotype.Component;

/// Market data tools served over MCP.
///
/// Errors are thrown, never returned: the MCP server turns a thrown exception into a tool result
/// with `isError: true` and the exception message as its text. A returned error string would be
/// reported to the agent as a success.
@Component
public class MarketDataTools {

  private final FinnhubClient finnhubClient;

  /// Creates the tools.
  ///
  /// @param finnhubClient source of market data
  public MarketDataTools(FinnhubClient finnhubClient) {
    this.finnhubClient = finnhubClient;
  }

  /// Returns the latest quote for a US-listed stock.
  ///
  /// @param symbol ticker symbol such as `AAPL`
  /// @return the quote, serialized to JSON text by LangChain4j
  /// @throws IllegalArgumentException when the symbol is not a valid US ticker
  /// @throws MarketDataException when there is no quote for the symbol or Finnhub fails
  @Tool(
      "Get the latest price quote for a US-listed stock by its ticker symbol, e.g. AAPL for Apple."
          + " Returns the current price, change, percent change, day high, low and open, previous"
          + " close, and asOfTime (UTC).")
  public Quote getQuote(@P("US ticker symbol, e.g. AAPL") String symbol) {
    String tickerSymbol = TickerSymbols.normalize(symbol);
    return finnhubClient
        .fetchQuote(tickerSymbol)
        .orElseThrow(
            () ->
                new MarketDataException(
                    "No quote available for symbol "
                        + tickerSymbol
                        + ". Only US-listed equities are supported."));
  }
}
