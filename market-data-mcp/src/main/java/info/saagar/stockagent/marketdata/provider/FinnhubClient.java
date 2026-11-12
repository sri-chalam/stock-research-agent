package info.saagar.stockagent.marketdata.provider;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/// Calls to the Finnhub market data API.
///
/// Arguments are already validated by the caller. Every method throws [MarketDataException] when
/// the call fails; its message is safe to show to the model.
public interface FinnhubClient {

  /// Fetches the latest quote for a ticker symbol.
  ///
  /// @param tickerSymbol validated, upper-case ticker symbol, e.g. `AAPL`
  /// @return the quote, or empty when Finnhub has no quote for the symbol
  /// @throws MarketDataException when the call fails; the message is safe to show to the model
  Optional<Quote> fetchQuote(String tickerSymbol);

  /// Fetches a compact company profile.
  ///
  /// @param tickerSymbol validated, upper-case ticker symbol
  /// @return the profile, or empty when Finnhub has none for the symbol
  Optional<CompanyProfile> fetchCompanyProfile(String tickerSymbol);

  /// Fetches the 10 most recent news articles about a company, newest first.
  ///
  /// @param tickerSymbol validated, upper-case ticker symbol
  /// @param fromDate first day of the range, inclusive
  /// @param toDate last day of the range, inclusive
  /// @return at most 10 articles, each summary cut to at most 300 characters
  List<NewsArticle> fetchCompanyNews(String tickerSymbol, LocalDate fromDate, LocalDate toDate);

  /// Fetches the 10 most recent market news articles in a category, newest first.
  ///
  /// @param newsCategory the news category
  /// @return at most 10 articles, each summary cut to at most 300 characters
  List<NewsArticle> fetchMarketNews(MarketNewsCategory newsCategory);

  /// Fetches earnings releases in a date range, earliest first.
  ///
  /// @param fromDate first day of the range, inclusive
  /// @param toDate last day of the range, inclusive
  /// @param tickerSymbol validated ticker symbol, or `null` for all companies
  /// @return at most 25 earnings releases
  List<EarningsEvent> fetchEarningsCalendar(
      LocalDate fromDate, LocalDate toDate, String tickerSymbol);

  /// Fetches the fixed set of key metrics in [BasicFinancials].
  ///
  /// @param tickerSymbol validated, upper-case ticker symbol
  /// @return the metrics, or empty when Finnhub has none for the symbol
  Optional<BasicFinancials> fetchBasicFinancials(String tickerSymbol);
}
