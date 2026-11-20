package info.saagar.stockagent.portfolio;

/// Points `@SpringBootTest` contexts at an in-memory H2 database in PostgreSQL mode, so the app's
/// `schema.sql` and the demo-portfolio seeding run unchanged against a real SQL store.
public final class PortfolioTestDatabase {

  /// Spring properties that replace the Postgres data source.
  public static final String URL_PROPERTY =
      "spring.datasource.url=jdbc:h2:mem:portfolio;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";

  /// H2's default user.
  public static final String USERNAME_PROPERTY = "spring.datasource.username=sa";

  /// H2's default (empty) password.
  public static final String PASSWORD_PROPERTY = "spring.datasource.password=";

  private PortfolioTestDatabase() {}
}
