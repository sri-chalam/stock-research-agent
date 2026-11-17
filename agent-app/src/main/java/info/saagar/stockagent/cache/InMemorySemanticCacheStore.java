package info.saagar.stockagent.cache;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.store.embedding.CosineSimilarity;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/// A [SemanticCacheStore] in a list, empty on every start. Used by tests and to run agent-app
/// without Postgres (`stock-agent.semantic-cache.store: in-memory`). Scans every row.
public class InMemorySemanticCacheStore implements SemanticCacheStore {

  private final List<CacheEntry> cacheEntries = new CopyOnWriteArrayList<>();

  @Override
  public Optional<CacheCandidate> findNearest(
      float[] questionEmbedding, String tickerKey, Instant now) {
    Embedding queryEmbedding = Embedding.from(questionEmbedding);
    return cacheEntries.stream()
        .filter(cacheEntry -> cacheEntry.tickerKey().equals(tickerKey))
        .filter(cacheEntry -> cacheEntry.expiresAt().isAfter(now))
        .map(
            cacheEntry ->
                new CacheCandidate(
                    cacheEntry.answer(),
                    cacheEntry.runTotalTokens(),
                    CosineSimilarity.between(
                        queryEmbedding, Embedding.from(cacheEntry.questionEmbedding()))))
        .max(Comparator.comparingDouble(CacheCandidate::similarity));
  }

  @Override
  public void save(CacheEntry cacheEntry) {
    cacheEntries.add(cacheEntry);
  }
}
