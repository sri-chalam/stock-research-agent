package info.saagar.stockagent.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/// Semantic cache settings bound from `stock-agent.semantic-cache` in `application.yml`.
///
/// @param enabled `false` skips every lookup and store
/// @param similarityThreshold lowest cosine similarity that counts as a hit
/// @param ttl how long a stored answer can be a hit
/// @param store `jdbc` (Postgres) or `in-memory`
/// @param database the `jdbc` store's Postgres connection; the password comes from
///   `SEM_CACHE_DB_PASSWORD`
@ConfigurationProperties("stock-agent.semantic-cache")
public record SemanticCacheProperties(
    boolean enabled, double similarityThreshold, Duration ttl, String store, Database database) {

  /// Postgres connection of the `jdbc` store.
  ///
  /// @param host database host
  /// @param port database port
  /// @param name database name
  /// @param user database user, whose search path is the `sem_cache` schema
  public record Database(String host, int port, String name, String user) {}
}
