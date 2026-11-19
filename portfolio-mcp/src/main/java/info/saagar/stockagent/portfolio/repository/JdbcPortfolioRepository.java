package info.saagar.stockagent.portfolio.repository;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/// [PortfolioRepository] over the `holding` and `watchlist_item` tables, which `schema.sql` creates
/// and [DemoPortfolioSeeder] seeds on startup.
public class JdbcPortfolioRepository implements PortfolioRepository {

  private final JdbcClient jdbcClient;

  /// Creates the repository.
  ///
  /// @param jdbcClient client over the portfolio database
  public JdbcPortfolioRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public List<Holding> findHoldings(String userId) {
    return jdbcClient
        .sql("SELECT ticker, shares FROM holding WHERE user_id = :userId ORDER BY ticker")
        .param("userId", userId)
        .query(Holding.class)
        .list();
  }

  @Override
  public List<String> findWatchlistTickers(String userId) {
    return jdbcClient
        .sql("SELECT ticker FROM watchlist_item WHERE user_id = :userId ORDER BY ticker")
        .param("userId", userId)
        .query(String.class)
        .list();
  }
}
