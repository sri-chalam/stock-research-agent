package info.saagar.stockagent.portfolio.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class DemoPortfolioSeederTest {

  private JdbcClient jdbcClient;
  private JdbcPortfolioRepository portfolioRepository;

  @BeforeEach
  void createEmptyPortfolioDatabase() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            "jdbc:h2:mem:seeder-"
                + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DB_CLOSE_DELAY=-1",
            "sa",
            "");
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    jdbcClient = JdbcClient.create(dataSource);
    portfolioRepository = new JdbcPortfolioRepository(jdbcClient);
  }

  @Test
  void claudeTestUsersGetCopiesOfTheDemoUsersPortfolios() {
    new DemoPortfolioSeeder(
            jdbcClient, "user_demo_1", "user_demo_2", "user_claude_1", "user_claude_2")
        .run(null);

    assertThat(portfolioRepository.findHoldings("user_claude_1"))
        .isEqualTo(portfolioRepository.findHoldings("user_demo_1"))
        .isNotEmpty();
    assertThat(portfolioRepository.findWatchlistTickers("user_claude_2"))
        .isEqualTo(portfolioRepository.findWatchlistTickers("user_demo_2"))
        .isNotEmpty();
  }

  @Test
  void blankClaudeTestUserSubsSeedNoExtraRows() {
    new DemoPortfolioSeeder(jdbcClient, "user_demo_1", "user_demo_2", "", " ").run(null);

    List<String> seededUserIds =
        jdbcClient
            .sql("SELECT DISTINCT user_id FROM holding ORDER BY user_id")
            .query(String.class)
            .list();
    assertThat(seededUserIds).containsExactly("user_demo_1", "user_demo_2");
  }
}
