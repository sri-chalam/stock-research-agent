package info.saagar.stockagent.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/// `schema.sql` and `data.sql` run on every start against a database that outlives the app, so
/// running them again must not fail or duplicate rows.
@SpringBootTest(
    properties = {
      PortfolioTestDatabase.URL_PROPERTY,
      PortfolioTestDatabase.USERNAME_PROPERTY,
      PortfolioTestDatabase.PASSWORD_PROPERTY
    })
class SeedDataTest {

  @Autowired private DataSource dataSource;
  @Autowired private JdbcClient jdbcClient;

  private long rowCount(String tableName) {
    return jdbcClient.sql("SELECT COUNT(*) FROM " + tableName).query(Long.class).single();
  }

  @Test
  void rerunningTheInitScriptsKeepsTheSeedRowsUnchanged() {
    long holdingCountAfterStart = rowCount("holding");
    long watchlistItemCountAfterStart = rowCount("watchlist_item");

    new ResourceDatabasePopulator(
            new ClassPathResource("schema.sql"), new ClassPathResource("data.sql"))
        .execute(dataSource);

    assertThat(holdingCountAfterStart).isEqualTo(5);
    assertThat(watchlistItemCountAfterStart).isEqualTo(5);
    assertThat(rowCount("holding")).isEqualTo(holdingCountAfterStart);
    assertThat(rowCount("watchlist_item")).isEqualTo(watchlistItemCountAfterStart);
  }
}
