package info.saagar.stockagent.marketdata.provider;

import java.math.BigDecimal;
import java.util.Optional;

/// In-memory [FinnhubClient] with synthetic data.
///
/// `AAPL` has a quote, `LIMIT` fails as if Finnhub returned 429, and every other symbol has no
/// quote, as Finnhub reports for unknown symbols.
public class FakeFinnhubClient implements FinnhubClient {

  /// Symbol that makes the fake fail with Finnhub's rate-limit error.
  public static final String RATE_LIMITED_SYMBOL = "LIMIT";

  /// The synthetic quote returned for `AAPL`.
  public static final Quote AAPL_QUOTE =
      new Quote(
          "AAPL",
          new BigDecimal("189.42"),
          new BigDecimal("-0.7"),
          new BigDecimal("-0.37"),
          new BigDecimal("191.05"),
          new BigDecimal("188.1"),
          new BigDecimal("190.2"),
          new BigDecimal("190.12"),
          "2026-05-01T14:30:00Z");

  @Override
  public Optional<Quote> fetchQuote(String tickerSymbol) {
    if (RATE_LIMITED_SYMBOL.equals(tickerSymbol)) {
      throw new MarketDataException(RestClientFinnhubClient.RATE_LIMIT_MESSAGE);
    }
    return "AAPL".equals(tickerSymbol) ? Optional.of(AAPL_QUOTE) : Optional.empty();
  }
}
