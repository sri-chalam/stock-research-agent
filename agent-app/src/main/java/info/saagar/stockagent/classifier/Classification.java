package info.saagar.stockagent.classifier;

import java.util.List;

/// The prompt classifier model's structured output, as the model returned it. Not validated:
/// [PromptClassifier] checks it before use.
///
/// @param freshness the prompt's freshness class
/// @param tickers tickers of the companies the prompt names, e.g. `AAPL` for "Apple"
public record Classification(FreshnessClass freshness, List<String> tickers) {}
