package info.saagar.stockagent.marketdata.provider;

import java.util.Optional;

/// Calls to the Finnhub market data API.
public interface FinnhubClient {

  /// Fetches the latest quote for a ticker symbol.
  ///
  /// @param tickerSymbol validated, upper-case ticker symbol, e.g. `AAPL`
  /// @return the quote, or empty when Finnhub has no quote for the symbol
  /// @throws MarketDataException when the call fails; the message is safe to show to the model
  Optional<Quote> fetchQuote(String tickerSymbol);
}
