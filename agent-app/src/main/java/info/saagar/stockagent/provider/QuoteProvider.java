package info.saagar.stockagent.provider;

import java.util.Optional;

/// Source of price quotes. Implementations hide the upstream data provider.
public interface QuoteProvider {

  /// Looks up the latest quote for a ticker symbol.
  ///
  /// @param tickerSymbol ticker symbol, case-insensitive
  /// @return the quote, or empty when the symbol is not supported
  Optional<Quote> findQuote(String tickerSymbol);
}
