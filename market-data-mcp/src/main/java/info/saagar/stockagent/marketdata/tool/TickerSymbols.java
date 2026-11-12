package info.saagar.stockagent.marketdata.tool;

import java.util.Locale;
import java.util.regex.Pattern;

/// Validates the ticker symbols the model passes to tools (FR-35).
public final class TickerSymbols {

  private static final Pattern US_TICKER_PATTERN = Pattern.compile("^[A-Z]{1,5}(\\.[A-Z])?$");

  private TickerSymbols() {}

  /// Normalises a ticker symbol and checks it looks like a US equity ticker.
  ///
  /// @param rawSymbol symbol as sent by the model, e.g. ` aapl ` or `BRK.B`
  /// @return the trimmed, upper-case symbol
  /// @throws IllegalArgumentException with a model-readable message when the symbol is invalid
  public static String normalize(String rawSymbol) {
    String candidateSymbol = rawSymbol == null ? "" : rawSymbol.strip().toUpperCase(Locale.ROOT);
    if (!US_TICKER_PATTERN.matcher(candidateSymbol).matches()) {
      throw new IllegalArgumentException(
          "Invalid ticker symbol. Provide a US ticker such as AAPL.");
    }
    return candidateSymbol;
  }
}
