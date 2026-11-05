package info.saagar.stockagent.provider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/// Hard-coded quotes for Iteration 0. Replaced by a Finnhub-backed MCP server in Iteration 1.
@Component
public class FakeQuoteProvider implements QuoteProvider {

  private static final Instant FAKE_AS_OF = Instant.parse("2026-05-01T14:30:00Z");

  private static final Map<String, Quote> QUOTES_BY_SYMBOL =
      Map.of(
          "AAPL", quote("AAPL", "Apple Inc.", "189.42", "-0.37"),
          "MSFT", quote("MSFT", "Microsoft Corporation", "421.15", "0.82"),
          "NVDA", quote("NVDA", "NVIDIA Corporation", "905.50", "1.94"),
          "TSLA", quote("TSLA", "Tesla, Inc.", "172.63", "-2.15"),
          "AMZN", quote("AMZN", "Amazon.com, Inc.", "183.90", "0.45"),
          "GOOGL", quote("GOOGL", "Alphabet Inc.", "164.28", "0.12"),
          "NFLX", quote("NFLX", "Netflix, Inc.", "612.74", "-0.91"));

  @Override
  public Optional<Quote> findQuote(String tickerSymbol) {
    if (tickerSymbol == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(QUOTES_BY_SYMBOL.get(tickerSymbol.strip().toUpperCase(Locale.ROOT)));
  }

  private static Quote quote(
      String symbol, String companyName, String priceText, String percentChangeText) {
    return new Quote(
        symbol,
        companyName,
        new BigDecimal(priceText),
        new BigDecimal(percentChangeText),
        FAKE_AS_OF);
  }
}
