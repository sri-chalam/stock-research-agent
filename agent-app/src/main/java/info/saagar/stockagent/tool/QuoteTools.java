package info.saagar.stockagent.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import info.saagar.stockagent.provider.Quote;
import info.saagar.stockagent.provider.QuoteProvider;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/// LangChain4j tools that expose price quotes to the Quote agent.
@Component
public class QuoteTools {

  private static final Pattern SYMBOL_PATTERN = Pattern.compile("^[A-Za-z]{1,5}(\\.[A-Za-z])?$");

  private final QuoteProvider quoteProvider;

  /// Creates the tools over a quote source.
  ///
  /// @param quoteProvider source of quotes
  public QuoteTools(QuoteProvider quoteProvider) {
    this.quoteProvider = quoteProvider;
  }

  /// Returns the latest quote for a US equity as model-readable text.
  ///
  /// @param symbol ticker symbol such as `AAPL`
  /// @return quote summary, or a message explaining that no quote is available
  @Tool("Get the latest price quote for a US stock by its ticker symbol, e.g. AAPL for Apple.")
  public String getQuote(@P("Ticker symbol, e.g. AAPL") String symbol) {
    if (symbol == null || !SYMBOL_PATTERN.matcher(symbol.strip()).matches()) {
      return "Invalid ticker symbol. Provide a US ticker such as AAPL.";
    }
    return quoteProvider
        .findQuote(symbol)
        .map(QuoteTools::describe)
        .orElseGet(
            () -> "No quote available for symbol " + symbol + ". Only US equities are supported.");
  }

  private static String describe(Quote quote) {
    return "%s (%s): price %s USD, change %s%% vs previous close, as of %s"
        .formatted(
            quote.symbol(),
            quote.companyName(),
            quote.price().toPlainString(),
            quote.percentChange().toPlainString(),
            quote.asOf());
  }
}
