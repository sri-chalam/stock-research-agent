package info.saagar.stockagent.cache;

import dev.langchain4j.data.embedding.Embedding;

/// The result of one semantic cache lookup.
public sealed interface CacheLookup {

  /// Returns the outcome logged as `cacheOutcome`.
  ///
  /// @return `HIT`, `MISS` or `SKIPPED`
  String cacheOutcome();

  /// A stored answer is similar enough: no agents run.
  ///
  /// @param cachedAnswer the stored answer
  record Hit(CacheCandidate cachedAnswer) implements CacheLookup {
    @Override
    public String cacheOutcome() {
      return "HIT";
    }
  }

  /// No stored answer is similar enough: the Supervisor runs and its answer may be stored.
  ///
  /// @param question the question as asked
  /// @param questionEmbedding the normalized question's embedding, reused when storing the answer
  /// @param tickerKey the question's sorted ticker set
  record Miss(String question, Embedding questionEmbedding, String tickerKey)
      implements CacheLookup {
    @Override
    public String cacheOutcome() {
      return "MISS";
    }
  }

  /// The cache was not searched, so the answer is not stored either.
  ///
  /// @param skipReason `disabled`, `realTime` or `lookupFailed`
  record Skipped(String skipReason) implements CacheLookup {
    @Override
    public String cacheOutcome() {
      return "SKIPPED";
    }
  }
}
