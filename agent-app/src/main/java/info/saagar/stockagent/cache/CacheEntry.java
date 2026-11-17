package info.saagar.stockagent.cache;

import info.saagar.stockagent.classifier.FreshnessClass;
import java.time.Instant;

/// One stored answer and the keys it is looked up by.
///
/// @param question the question as asked
/// @param questionEmbedding embedding of the normalized question (384 dimensions)
/// @param tickerKey sorted ticker set, as built by [SemanticCache#tickerKey]
/// @param freshnessClass the question's freshness class; always `CACHEABLE` today
/// @param answer the Supervisor's answer
/// @param runTotalTokens input plus output tokens the Supervisor run used, saved by every hit
/// @param expiresAt instant after which the row is never a hit
public record CacheEntry(
    String question,
    float[] questionEmbedding,
    String tickerKey,
    FreshnessClass freshnessClass,
    String answer,
    long runTotalTokens,
    Instant expiresAt) {}
