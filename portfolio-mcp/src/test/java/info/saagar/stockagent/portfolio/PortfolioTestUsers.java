package info.saagar.stockagent.portfolio;

/// The two demo users that `@SpringBootTest` contexts seed, standing in for the WorkOS `sub` values
/// that `WORKOS_DEMO_USER_1_SUB` and `WORKOS_DEMO_USER_2_SUB` supply in a real run.
public final class PortfolioTestUsers {

  /// Token `sub` of demo user 1.
  public static final String USER_1_SUB = "user_test_1";

  /// Token `sub` of demo user 2.
  public static final String USER_2_SUB = "user_test_2";

  /// Spring property that sets demo user 1's `sub`.
  public static final String USER_1_SUB_PROPERTY =
      "stock-agent.demo-users.user-1-sub=" + USER_1_SUB;

  /// Spring property that sets demo user 2's `sub`.
  public static final String USER_2_SUB_PROPERTY =
      "stock-agent.demo-users.user-2-sub=" + USER_2_SUB;

  /// Spring property that leaves Claude's test user 1 unseeded, even when the developer's shell
  /// exports `CLAUDE_TEST_USER_1_SUB`.
  public static final String NO_CLAUDE_TEST_USER_1_PROPERTY =
      "stock-agent.demo-users.claude-test-user-1-sub=";

  /// Spring property that leaves Claude's test user 2 unseeded, even when the developer's shell
  /// exports `CLAUDE_TEST_USER_2_SUB`.
  public static final String NO_CLAUDE_TEST_USER_2_PROPERTY =
      "stock-agent.demo-users.claude-test-user-2-sub=";

  private PortfolioTestUsers() {}
}
