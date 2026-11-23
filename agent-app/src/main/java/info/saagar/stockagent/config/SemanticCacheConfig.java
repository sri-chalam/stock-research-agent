package info.saagar.stockagent.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;
import info.saagar.stockagent.cache.InMemorySemanticCacheStore;
import info.saagar.stockagent.cache.JdbcSemanticCacheStore;
import info.saagar.stockagent.cache.SemanticCache;
import info.saagar.stockagent.cache.SemanticCacheStore;
import io.opentelemetry.api.trace.Tracer;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/// Wires the semantic cache: the in-process embedding model and the answer store.
///
/// `stock-agent.semantic-cache.store` picks the store: `jdbc` (the default) or `in-memory`, which
/// tests use and which runs agent-app without Postgres.
@Configuration
@EnableConfigurationProperties(SemanticCacheProperties.class)
public class SemanticCacheConfig {

  private static final Logger log = LoggerFactory.getLogger(SemanticCacheConfig.class);

  private static final String STORE_PROPERTY = "stock-agent.semantic-cache.store";

  private static final String SCHEMA_SCRIPT = "sem-cache-schema.sql";

  /// Builds the embedding model: quantized bge-small-en-v1.5 over ONNX, the model `filings-mcp`
  /// uses, in process with no API key or network call.
  ///
  /// @return the embedding model
  @Bean
  public EmbeddingModel questionEmbeddingModel() {
    return new BgeSmallEnV15QuantizedEmbeddingModel();
  }

  /// Builds the connection pool of the `jdbc` store.
  ///
  /// Every connection turns on pgvector's strict-order iterative index scan, so an HNSW search
  /// filtered on the ticker set keeps scanning until it finds a matching row, instead of returning
  /// nothing when the first candidates all have other tickers.
  ///
  /// @param semanticCacheProperties database host, port, name and user
  /// @param databasePassword password from the `SEM_CACHE_DB_PASSWORD` environment variable
  /// @return the pool; it connects on creation, so startup fails when Postgres is unreachable
  @Bean
  @ConditionalOnProperty(name = STORE_PROPERTY, havingValue = "jdbc", matchIfMissing = true)
  public HikariDataSource semCacheDataSource(
      SemanticCacheProperties semanticCacheProperties,
      @Value("${SEM_CACHE_DB_PASSWORD}") String databasePassword) {
    SemanticCacheProperties.Database database = semanticCacheProperties.database();
    HikariConfig poolConfig = new HikariConfig();
    poolConfig.setPoolName("sem-cache");
    poolConfig.setJdbcUrl(
        "jdbc:postgresql://" + database.host() + ":" + database.port() + "/" + database.name());
    poolConfig.setUsername(database.user());
    poolConfig.setPassword(databasePassword);
    poolConfig.setConnectionInitSql("SET hnsw.iterative_scan = strict_order");
    return new HikariDataSource(poolConfig);
  }

  /// Builds the `jdbc` store after creating its table and index with the idempotent schema script,
  /// which runs on every start.
  ///
  /// @param semCacheDataSource the connection pool
  /// @return the store
  @Bean
  @ConditionalOnProperty(name = STORE_PROPERTY, havingValue = "jdbc", matchIfMissing = true)
  public SemanticCacheStore jdbcSemanticCacheStore(HikariDataSource semCacheDataSource) {
    long startNanos = System.nanoTime();
    new ResourceDatabasePopulator(new ClassPathResource(SCHEMA_SCRIPT)).execute(semCacheDataSource);
    log.atInfo()
        .addKeyValue("eventName", "semCacheSchemaApplied")
        .addKeyValue(
            "eventData",
            Map.of(
                "schemaScript",
                SCHEMA_SCRIPT,
                "schemaApplyDurationTimeMs",
                (System.nanoTime() - startNanos) / 1_000_000))
        .log("Semantic cache schema applied");
    return new JdbcSemanticCacheStore(JdbcClient.create(semCacheDataSource));
  }

  /// Builds an in-memory store, empty on every start.
  ///
  /// @return the store
  @Bean
  @ConditionalOnProperty(name = STORE_PROPERTY, havingValue = "in-memory")
  public SemanticCacheStore inMemorySemanticCacheStore() {
    return new InMemorySemanticCacheStore();
  }

  /// Builds the semantic cache on the system clock.
  ///
  /// @param questionEmbeddingModel embeds normalized questions
  /// @param semanticCacheStore the answer store
  /// @param semanticCacheProperties switch, threshold and TTL
  /// @param tracer creates the question-embedding span
  /// @return the semantic cache
  @Bean
  public SemanticCache semanticCache(
      EmbeddingModel questionEmbeddingModel,
      SemanticCacheStore semanticCacheStore,
      SemanticCacheProperties semanticCacheProperties,
      Tracer tracer) {
    return new SemanticCache(
        questionEmbeddingModel,
        semanticCacheStore,
        semanticCacheProperties,
        Clock.systemUTC(),
        tracer);
  }
}
