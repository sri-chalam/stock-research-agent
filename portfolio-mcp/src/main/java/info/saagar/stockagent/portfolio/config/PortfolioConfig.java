package info.saagar.stockagent.portfolio.config;

import dev.langchain4j.community.mcp.server.McpServer;
import dev.langchain4j.mcp.protocol.McpImplementation;
import info.saagar.stockagent.portfolio.repository.DemoPortfolioSeeder;
import info.saagar.stockagent.portfolio.repository.JdbcPortfolioRepository;
import info.saagar.stockagent.portfolio.repository.PortfolioRepository;
import info.saagar.stockagent.portfolio.tool.PortfolioTools;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/// Wires the portfolio store and the MCP server that serves the portfolio tools.
@Configuration
public class PortfolioConfig {

  /// Builds the store over the auto-configured data source.
  ///
  /// @param jdbcClient client over the portfolio database
  /// @return the repository
  @Bean
  public PortfolioRepository portfolioRepository(JdbcClient jdbcClient) {
    return new JdbcPortfolioRepository(jdbcClient);
  }

  /// Seeds the demo users' portfolios on startup, after `schema.sql` has run.
  ///
  /// @param jdbcClient client over the portfolio database
  /// @param user1Sub token `sub` of demo user 1, from `WORKOS_DEMO_USER_1_SUB`
  /// @param user2Sub token `sub` of demo user 2, from `WORKOS_DEMO_USER_2_SUB`
  /// @param claudeTestUser1Sub token `sub` of Claude's test user 1, from `CLAUDE_TEST_USER_1_SUB`;
  ///   blank when not configured
  /// @param claudeTestUser2Sub token `sub` of Claude's test user 2, from `CLAUDE_TEST_USER_2_SUB`;
  ///   blank when not configured
  /// @return the seeder, run once by Spring Boot after startup
  @Bean
  public DemoPortfolioSeeder demoPortfolioSeeder(
      JdbcClient jdbcClient,
      @Value("${stock-agent.demo-users.user-1-sub}") String user1Sub,
      @Value("${stock-agent.demo-users.user-2-sub}") String user2Sub,
      @Value("${stock-agent.demo-users.claude-test-user-1-sub:}") String claudeTestUser1Sub,
      @Value("${stock-agent.demo-users.claude-test-user-2-sub:}") String claudeTestUser2Sub) {
    return new DemoPortfolioSeeder(
        jdbcClient, user1Sub, user2Sub, claudeTestUser1Sub, claudeTestUser2Sub);
  }

  /// Builds the transport-agnostic MCP server over the portfolio tools.
  ///
  /// @param portfolioTools the `@Tool` methods to expose
  /// @param serverName name reported in `initialize`, from `spring.application.name`
  /// @param serverVersion version reported in `initialize`; `dev` when not run from a built jar
  /// @return the MCP server
  @Bean
  public McpServer mcpServer(
      PortfolioTools portfolioTools,
      @Value("${spring.application.name}") String serverName,
      @Value("${spring.application.version:dev}") String serverVersion) {
    McpImplementation serverInfo = new McpImplementation();
    serverInfo.setName(serverName);
    serverInfo.setVersion(serverVersion);
    return new McpServer(List.of(portfolioTools), serverInfo);
  }
}
