package info.saagar.stockagent.classifier;

import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;

/// A prompt's validated classification, which decides whether the prompt is refused and whether the
/// semantic cache is used.
///
/// @param freshnessClass the freshness class; `REAL_TIME` when the classifier failed or returned an
///   invalid ticker
/// @param tickers validated uppercase tickers; empty when the classifier failed
/// @param inputGuardrailVerdict whether the prompt is refused; `SKIPPED` when the classifier failed
///   or returned no flags
/// @param classifierResponse the classifier model's response, for its tokens and model name; `null`
///   when the classifier call threw
public record ClassifiedPrompt(
    FreshnessClass freshnessClass,
    List<String> tickers,
    InputGuardrailVerdict inputGuardrailVerdict,
    ChatResponse classifierResponse) {

  /// The classification used when the classifier's freshness class or tickers cannot be trusted:
  /// `REAL_TIME`, so the cache is skipped and the Supervisor runs as usual.
  ///
  /// @param inputGuardrailVerdict the guardrail verdict, which may still come from valid flags
  /// @param classifierResponse the model response, or `null` when the call threw
  /// @return a `REAL_TIME` classification with no tickers
  static ClassifiedPrompt realTimeFallback(
      InputGuardrailVerdict inputGuardrailVerdict, ChatResponse classifierResponse) {
    return new ClassifiedPrompt(
        FreshnessClass.REAL_TIME, List.of(), inputGuardrailVerdict, classifierResponse);
  }
}
