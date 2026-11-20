package info.saagar.stockagent.portfolio.repository;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;

/// Seeds synthetic portfolios for the two WorkOS demo users, keyed by their token `sub`, on every
/// start. The `sub` values come from configuration (env vars), never from committed SQL.
///
/// At most 3 holdings each, so the Market data agent can quote every holding within its max-steps.
/// `ON CONFLICT DO NOTHING` keeps restarts from duplicating rows.
public class DemoPortfolioSeeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(DemoPortfolioSeeder.class);

  private static final List<Holding> USER_1_HOLDINGS =
      List.of(new Holding("AAPL", 50), new Holding("MSFT", 30), new Holding("NVDA", 20));
  private static final List<String> USER_1_WATCHLIST = List.of("GOOGL", "AMZN");
  private static final List<Holding> USER_2_HOLDINGS =
      List.of(new Holding("JPM", 40), new Holding("XOM", 60));
  private static final List<String> USER_2_WATCHLIST = List.of("TSLA", "META", "NFLX");

  private final JdbcClient jdbcClient;
  private final String user1Sub;
  private final String user2Sub;

  /// Creates the seeder.
  ///
  /// @param jdbcClient client over the portfolio database
  /// @param user1Sub token `sub` of demo user 1
  /// @param user2Sub token `sub` of demo user 2
  public DemoPortfolioSeeder(JdbcClient jdbcClient, String user1Sub, String user2Sub) {
    this.jdbcClient = jdbcClient;
    this.user1Sub = user1Sub;
    this.user2Sub = user2Sub;
  }

  /// Inserts the demo rows that are not there yet.
  ///
  /// @param applicationArguments unused
  @Override
  public void run(ApplicationArguments applicationArguments) {
    int insertedRowCount =
        seedUser(user1Sub, USER_1_HOLDINGS, USER_1_WATCHLIST)
            + seedUser(user2Sub, USER_2_HOLDINGS, USER_2_WATCHLIST);
    log.atInfo()
        .addKeyValue("eventName", "demoPortfoliosSeeded")
        .addKeyValue("eventData", Map.of("demoUserCount", 2, "insertedRowCount", insertedRowCount))
        .log("Demo portfolios seeded");
  }

  private int seedUser(String userId, List<Holding> holdings, List<String> watchlistTickers) {
    int insertedRowCount = 0;
    for (Holding holding : holdings) {
      insertedRowCount +=
          jdbcClient
              .sql(
                  "INSERT INTO holding (user_id, ticker, shares) VALUES (:userId, :ticker, :shares)"
                      + " ON CONFLICT DO NOTHING")
              .param("userId", userId)
              .param("ticker", holding.ticker())
              .param("shares", holding.shares())
              .update();
    }
    for (String ticker : watchlistTickers) {
      insertedRowCount +=
          jdbcClient
              .sql(
                  "INSERT INTO watchlist_item (user_id, ticker) VALUES (:userId, :ticker)"
                      + " ON CONFLICT DO NOTHING")
              .param("userId", userId)
              .param("ticker", ticker)
              .update();
    }
    return insertedRowCount;
  }
}
