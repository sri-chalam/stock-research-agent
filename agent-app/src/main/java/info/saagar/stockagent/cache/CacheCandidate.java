package info.saagar.stockagent.cache;

/// The stored answer nearest to a question, before the similarity threshold is applied.
///
/// @param answer the stored answer
/// @param runTotalTokens tokens the run that produced the answer used
/// @param similarity cosine similarity between the two questions' embeddings
public record CacheCandidate(String answer, long runTotalTokens, double similarity) {}
