package info.saagar.stockagent.portfolio.repository;

import java.util.List;

/// Reads users' holdings and watchlists. Every read is scoped to one user.
public interface PortfolioRepository {

  /// Returns the user's holdings, ordered by ticker.
  ///
  /// @param userId the user whose holdings to read
  /// @return the holdings; empty for an unknown user
  List<Holding> findHoldings(String userId);

  /// Returns the tickers on the user's watchlist, in alphabetical order.
  ///
  /// @param userId the user whose watchlist to read
  /// @return the tickers; empty for an unknown user
  List<String> findWatchlistTickers(String userId);
}
