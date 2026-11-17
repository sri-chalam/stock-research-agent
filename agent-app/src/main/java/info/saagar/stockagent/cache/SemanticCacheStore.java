package info.saagar.stockagent.cache;

import java.time.Instant;
import java.util.Optional;

/// Storage of the semantic cache's answers. [SemanticCache] holds the hit, miss and store rules; a
/// store only filters, ranks and saves rows.
public interface SemanticCacheStore {

  /// Finds the stored answer whose question is nearest to the given embedding, among the rows with
  /// exactly this ticker set that have not expired.
  ///
  /// @param questionEmbedding embedding of the normalized question
  /// @param tickerKey the question's sorted ticker set, as built by [SemanticCache#tickerKey]
  /// @param now rows that expire at or before this instant are ignored
  /// @return the nearest row and its cosine similarity; empty when no row matches the filters
  Optional<CacheCandidate> findNearest(float[] questionEmbedding, String tickerKey, Instant now);

  /// Saves one answer.
  ///
  /// @param cacheEntry the answer and its lookup keys
  void save(CacheEntry cacheEntry);
}
