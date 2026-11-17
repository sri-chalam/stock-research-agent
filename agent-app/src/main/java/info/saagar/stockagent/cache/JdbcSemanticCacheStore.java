package info.saagar.stockagent.cache;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/// A [SemanticCacheStore] over the `sem_cache.semantic_cache_entry` table, created by
/// `sem-cache-schema.sql`. The nearest-row query orders by pgvector's cosine distance (`<=>`),
/// which the table's HNSW index serves.
public class JdbcSemanticCacheStore implements SemanticCacheStore {

  private static final String FIND_NEAREST_SQL =
      """
      SELECT answer, run_total_tokens,
             1 - (question_embedding <=> CAST(:questionEmbedding AS vector)) AS similarity
      FROM sem_cache.semantic_cache_entry
      WHERE ticker_key = :tickerKey AND expires_at > :now
      ORDER BY question_embedding <=> CAST(:questionEmbedding AS vector)
      LIMIT 1
      """;

  private static final String INSERT_SQL =
      """
      INSERT INTO sem_cache.semantic_cache_entry
          (question, question_embedding, ticker_key, freshness_class, answer, run_total_tokens,
           expires_at)
      VALUES (:question, CAST(:questionEmbedding AS vector), :tickerKey, :freshnessClass, :answer,
              :runTotalTokens, :expiresAt)
      """;

  private final JdbcClient jdbcClient;

  /// Creates the store.
  ///
  /// @param jdbcClient client over the `sem_cache` user's connection pool
  public JdbcSemanticCacheStore(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public Optional<CacheCandidate> findNearest(
      float[] questionEmbedding, String tickerKey, Instant now) {
    return jdbcClient
        .sql(FIND_NEAREST_SQL)
        .param("questionEmbedding", toVectorLiteral(questionEmbedding))
        .param("tickerKey", tickerKey)
        .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
        .query(
            (resultSet, rowNumber) ->
                new CacheCandidate(
                    resultSet.getString("answer"),
                    resultSet.getLong("run_total_tokens"),
                    resultSet.getDouble("similarity")))
        .optional();
  }

  @Override
  public void save(CacheEntry cacheEntry) {
    jdbcClient
        .sql(INSERT_SQL)
        .param("question", cacheEntry.question())
        .param("questionEmbedding", toVectorLiteral(cacheEntry.questionEmbedding()))
        .param("tickerKey", cacheEntry.tickerKey())
        .param("freshnessClass", cacheEntry.freshnessClass().name())
        .param("answer", cacheEntry.answer())
        .param("runTotalTokens", cacheEntry.runTotalTokens())
        .param("expiresAt", OffsetDateTime.ofInstant(cacheEntry.expiresAt(), ZoneOffset.UTC))
        .update();
  }

  /// Formats an embedding as pgvector's text input, e.g. `[0.1,0.2]`.
  private static String toVectorLiteral(float[] embeddingVector) {
    StringBuilder vectorLiteral = new StringBuilder(embeddingVector.length * 12).append('[');
    for (int dimensionIndex = 0; dimensionIndex < embeddingVector.length; dimensionIndex++) {
      if (dimensionIndex > 0) {
        vectorLiteral.append(',');
      }
      vectorLiteral.append(embeddingVector[dimensionIndex]);
    }
    return vectorLiteral.append(']').toString();
  }
}
