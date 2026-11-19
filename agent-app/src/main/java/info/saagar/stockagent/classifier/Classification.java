package info.saagar.stockagent.classifier;

import java.util.List;

/// The prompt classifier model's structured output, as the model returned it. Not validated:
/// [PromptClassifier] checks it before use.
///
/// @param freshness the prompt's freshness class
/// @param tickers tickers of the companies the prompt names, e.g. `AAPL` for "Apple"
/// @param injectionSuspected whether the prompt seems to try to override the assistant's
///   instructions or reach other users' data
/// @param adviceRequest whether the prompt asks for personalized buy, sell or hold advice
public record Classification(
    FreshnessClass freshness,
    List<String> tickers,
    Boolean injectionSuspected,
    Boolean adviceRequest) {}
