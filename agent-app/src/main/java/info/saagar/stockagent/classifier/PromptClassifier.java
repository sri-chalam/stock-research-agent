package info.saagar.stockagent.classifier;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.Result;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Classifies each chat prompt into a freshness class, the tickers it names and an input guardrail
/// verdict, before the Supervisor runs.
///
/// A classification that cannot be trusted never fails the request: when the call throws, returns
/// no freshness class or names an invalid ticker, the prompt is treated as `REAL_TIME`, so the
/// cache is skipped. When the call throws or returns no guardrail flags, the guardrail fails open
/// (`SKIPPED`) and `guardrailSkipped` is logged. There is no retry.
public class PromptClassifier {

  private static final Logger log = LoggerFactory.getLogger(PromptClassifier.class);

  private static final Pattern TICKER_PATTERN = Pattern.compile("^[A-Z]{1,5}$");

  private final ClassifierAiService classifierAiService;

  /// Creates the classifier.
  ///
  /// @param classifierAiService the classifier's LLM call
  public PromptClassifier(ClassifierAiService classifierAiService) {
    this.classifierAiService = classifierAiService;
  }

  /// Classifies a prompt.
  ///
  /// With the OpenTelemetry Java agent, each call is a span (ADR 0013), with the classifier's LLM
  /// call under it.
  ///
  /// @param prompt the user's chat prompt
  /// @return the validated classification, or a `REAL_TIME` fallback
  @WithSpan
  public ClassifiedPrompt classify(String prompt) {
    long startNanos = System.nanoTime();
    Result<Classification> classifierResult;
    try {
      classifierResult = classifierAiService.classify(prompt);
    } catch (RuntimeException classifierException) {
      Map<String, Object> skipData = new LinkedHashMap<>();
      skipData.put("skipReason", "classifierFailed");
      skipData.put("exceptionType", classifierException.getClass().getName());
      skipData.put("exceptionMessage", String.valueOf(classifierException.getMessage()));
      skipData.put("classifyDurationTimeMs", elapsedMs(startNanos));
      logCacheSkipped(skipData);
      logGuardrailSkipped("classifierFailed");
      return ClassifiedPrompt.realTimeFallback(InputGuardrailVerdict.SKIPPED, null);
    }
    Classification classification = classifierResult.content();
    ChatResponse classifierResponse = classifierResult.finalResponse();
    InputGuardrailVerdict inputGuardrailVerdict = inputGuardrailVerdict(classification);
    if (classification.freshness() == null || classification.tickers() == null) {
      logCacheSkipped(
          Map.of(
              "skipReason",
              "invalidClassification",
              "classifyDurationTimeMs",
              elapsedMs(startNanos)));
      return ClassifiedPrompt.realTimeFallback(inputGuardrailVerdict, classifierResponse);
    }
    List<String> tickers =
        classification.tickers().stream()
            .map(ticker -> ticker == null ? "" : ticker.toUpperCase(Locale.ROOT))
            .toList();
    List<String> invalidTickers =
        tickers.stream().filter(ticker -> !TICKER_PATTERN.matcher(ticker).matches()).toList();
    if (!invalidTickers.isEmpty()) {
      logCacheSkipped(
          Map.of(
              "skipReason",
              "invalidTicker",
              "invalidTickers",
              invalidTickers,
              "classifyDurationTimeMs",
              elapsedMs(startNanos)));
      return ClassifiedPrompt.realTimeFallback(inputGuardrailVerdict, classifierResponse);
    }
    log.atInfo()
        .addKeyValue("eventName", "promptClassified")
        .addKeyValue(
            "eventData",
            Map.of(
                "freshnessClass",
                classification.freshness(),
                "tickers",
                tickers,
                "inputGuardrailVerdict",
                inputGuardrailVerdict,
                "classifyDurationTimeMs",
                elapsedMs(startNanos)))
        .log("Prompt classified");
    return new ClassifiedPrompt(
        classification.freshness(), tickers, inputGuardrailVerdict, classifierResponse);
  }

  /// Reads the guardrail flags, which are trusted even when the freshness class or a ticker is not:
  /// injection wins over advice, and a missing flag fails open.
  private static InputGuardrailVerdict inputGuardrailVerdict(Classification classification) {
    if (classification.injectionSuspected() == null || classification.adviceRequest() == null) {
      logGuardrailSkipped("missingGuardrailFlags");
      return InputGuardrailVerdict.SKIPPED;
    }
    if (classification.injectionSuspected()) {
      return InputGuardrailVerdict.INJECTION;
    }
    return classification.adviceRequest()
        ? InputGuardrailVerdict.ADVICE
        : InputGuardrailVerdict.PASSED;
  }

  private static void logGuardrailSkipped(String skipReason) {
    log.atWarn()
        .addKeyValue("eventName", "guardrailSkipped")
        .addKeyValue("eventData", Map.of("skipReason", skipReason))
        .log("Input guardrail skipped: classifier flags unavailable");
  }

  private static void logCacheSkipped(Map<String, Object> skipData) {
    log.atWarn()
        .addKeyValue("eventName", "semanticCacheSkipped")
        .addKeyValue("eventData", skipData)
        .log("Semantic cache skipped: classification unusable");
  }

  private static long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
