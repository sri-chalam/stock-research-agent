package info.saagar.stockagent.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import info.saagar.stockagent.common.testing.TestJwts;
import info.saagar.stockagent.portfolio.repository.DemoPortfolioSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/// `schema.sql` and the demo-portfolio seeding run on every start against a database that outlives
/// the app, so running them again must not fail or duplicate rows.
@SpringBootTest(
    properties = {
      PortfolioTestDatabase.URL_PROPERTY,
      PortfolioTestDatabase.USERNAME_PROPERTY,
      PortfolioTestDatabase.PASSWORD_PROPERTY,
      PortfolioTestUsers.USER_1_SUB_PROPERTY,
      PortfolioTestUsers.USER_2_SUB_PROPERTY
    })
class SeedDataTest {

  @DynamicPropertySource
  static void trustTheTestIssuer(DynamicPropertyRegistry propertyRegistry) {
    TestJwts.registerResourceServerProperties(propertyRegistry);
  }

  @Autowired private DemoPortfolioSeeder demoPortfolioSeeder;
  @Autowired private JdbcClient jdbcClient;

  private long rowCount(String tableName) {
    return jdbcClient.sql("SELECT COUNT(*) FROM " + tableName).query(Long.class).single();
  }

  @Test
  void reseedingKeepsTheSeedRowsUnchanged() {
    long holdingCountAfterStart = rowCount("holding");
    long watchlistItemCountAfterStart = rowCount("watchlist_item");

    demoPortfolioSeeder.run(null);

    assertThat(holdingCountAfterStart).isEqualTo(5);
    assertThat(watchlistItemCountAfterStart).isEqualTo(5);
    assertThat(rowCount("holding")).isEqualTo(holdingCountAfterStart);
    assertThat(rowCount("watchlist_item")).isEqualTo(watchlistItemCountAfterStart);
  }
}
