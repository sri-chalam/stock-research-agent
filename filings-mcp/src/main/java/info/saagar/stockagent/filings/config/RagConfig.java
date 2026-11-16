package info.saagar.stockagent.filings.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/// Wires the filings RAG: the in-process embedding model and the chunk store.
///
/// `filings.rag.vector-store` picks the store: `pgvector` (the default) or `in-memory`, which tests
/// use and which runs filings-mcp without Postgres.
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {

  /// Embedding dimension of bge-small-en-v1.5.
  static final int EMBEDDING_DIMENSION = 384;

  private static final String VECTOR_STORE_PROPERTY = "filings.rag.vector-store";

  /// Builds the embedding model: quantized bge-small-en-v1.5 over ONNX, in process, with no API key
  /// or network call. It reads at most 512 tokens of each input and silently drops the rest.
  ///
  /// @return the embedding model
  @Bean
  public EmbeddingModel embeddingModel() {
    return new BgeSmallEnV15QuantizedEmbeddingModel();
  }

  /// Builds the connection pool for the pgvector store. A pool keeps `searchFilings` from opening a
  /// new connection per call.
  ///
  /// @param ragProperties database host, port, name and user
  /// @param databasePassword password from the `FILINGS_DB_PASSWORD` environment variable
  /// @return the pool; it connects on creation, so startup fails when Postgres is unreachable
  @Bean
  @ConditionalOnProperty(
      name = VECTOR_STORE_PROPERTY,
      havingValue = "pgvector",
      matchIfMissing = true)
  public HikariDataSource filingsDataSource(
      RagProperties ragProperties, @Value("${FILINGS_DB_PASSWORD}") String databasePassword) {
    RagProperties.Database database = ragProperties.database();
    HikariConfig poolConfig = new HikariConfig();
    poolConfig.setPoolName("filings-pgvector");
    poolConfig.setJdbcUrl(
        "jdbc:postgresql://" + database.host() + ":" + database.port() + "/" + database.name());
    poolConfig.setUsername(database.user());
    poolConfig.setPassword(databasePassword);
    return new HikariDataSource(poolConfig);
  }

  /// Builds the pgvector chunk store. It creates its table, schema-qualified into the `filings`
  /// schema, when missing. A schema change is handled by dropping the table and re-ingesting.
  ///
  /// The `vector` extension is created by the database init script, since the filings user may not
  /// create extensions.
  ///
  /// @param filingsDataSource the connection pool
  /// @param ragProperties holds the table name
  /// @return the store
  @Bean
  @ConditionalOnProperty(
      name = VECTOR_STORE_PROPERTY,
      havingValue = "pgvector",
      matchIfMissing = true)
  public EmbeddingStore<TextSegment> pgVectorFilingChunkStore(
      HikariDataSource filingsDataSource, RagProperties ragProperties) {
    return PgVectorEmbeddingStore.datasourceBuilder()
        .datasource(filingsDataSource)
        .table(ragProperties.database().table())
        .dimension(EMBEDDING_DIMENSION)
        .createTable(true)
        .skipCreateVectorExtension(true)
        .build();
  }

  /// Builds an in-memory chunk store, empty on every start.
  ///
  /// @return the store
  @Bean
  @ConditionalOnProperty(name = VECTOR_STORE_PROPERTY, havingValue = "in-memory")
  public EmbeddingStore<TextSegment> inMemoryFilingChunkStore() {
    return new InMemoryEmbeddingStore<>();
  }
}
