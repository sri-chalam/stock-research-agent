package info.saagar.stockagent.classifier;

/// Whether an answer to a prompt may be reused for a while, which decides whether the semantic
/// cache is used for it.
public enum FreshnessClass {
  /// Prices and intraday moves: never cached.
  REAL_TIME,
  /// Anything else, such as filing content, news summaries and company descriptions.
  CACHEABLE
}
