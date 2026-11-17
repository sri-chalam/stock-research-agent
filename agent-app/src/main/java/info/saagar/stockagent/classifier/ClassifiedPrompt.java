package info.saagar.stockagent.classifier;

import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;

/// A prompt's validated classification, which decides whether the semantic cache is used.
///
/// @param freshnessClass the freshness class; `REAL_TIME` when the classifier failed or returned an
///   invalid ticker
/// @param tickers validated uppercase tickers; empty when the classifier failed
/// @param classifierResponse the classifier model's response, for its tokens and model name; `null`
///   when the classifier call threw
public record ClassifiedPrompt(
    FreshnessClass freshnessClass, List<String> tickers, ChatResponse classifierResponse) {

  /// The classification used when the classifier's answer cannot be trusted: `REAL_TIME`, so the
  /// cache is skipped and the Supervisor runs as usual.
  ///
  /// @param classifierResponse the model response, or `null` when the call threw
  /// @return a `REAL_TIME` classification with no tickers
  static ClassifiedPrompt realTimeFallback(ChatResponse classifierResponse) {
    return new ClassifiedPrompt(FreshnessClass.REAL_TIME, List.of(), classifierResponse);
  }
}
