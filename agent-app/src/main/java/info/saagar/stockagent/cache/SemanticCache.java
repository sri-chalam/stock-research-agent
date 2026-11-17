package info.saagar.stockagent.cache;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.service.tool.ToolExecution;
import info.saagar.stockagent.classifier.ClassifiedPrompt;
import info.saagar.stockagent.classifier.FreshnessClass;
import info.saagar.stockagent.config.PortfolioAgentConfig;
import info.saagar.stockagent.config.PromptClassifierConfig;
import info.saagar.stockagent.config.SemanticCacheProperties;
import info.saagar.stockagent.logging.RunProvenance;
import info.saagar.stockagent.logging.RunProvenance.AgentCall;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// The semantic cache's hit, miss and store rules.
///
/// A lookup searches only for a `CACHEABLE` prompt when the cache is enabled. It takes the nearest
/// unexpired stored question with exactly the same ticker set and is a hit when their cosine
/// similarity reaches the configured threshold.
///
/// After a miss, the Supervisor's answer is stored only when no portfolio tool ran (the cache has
/// no user scope), no tool call failed and the answer is not blank. A lookup or store failure never
/// fails the request: it is logged and the request goes on without the cache.
public class SemanticCache {

  private static final Logger log = LoggerFactory.getLogger(SemanticCache.class);

  private final EmbeddingModel embeddingModel;
  private final SemanticCacheStore semanticCacheStore;
  private final SemanticCacheProperties semanticCacheProperties;
  private final Clock clock;

  /// Creates the cache.
  ///
  /// @param embeddingModel embeds normalized questions
  /// @param semanticCacheStore stores and searches the answers
  /// @param semanticCacheProperties switch, threshold and TTL
  /// @param clock decides expiry
  public SemanticCache(
      EmbeddingModel embeddingModel,
      SemanticCacheStore semanticCacheStore,
      SemanticCacheProperties semanticCacheProperties,
      Clock clock) {
    this.embeddingModel = embeddingModel;
    this.semanticCacheStore = semanticCacheStore;
    this.semanticCacheProperties = semanticCacheProperties;
    this.clock = clock;
  }

  /// Looks a prompt up and logs `semanticCacheLookup`.
  ///
  /// @param question the user's prompt
  /// @param classifiedPrompt the prompt's freshness class and tickers
  /// @return a hit with the stored answer, a miss to store the answer with, or a skip
  public CacheLookup lookup(String question, ClassifiedPrompt classifiedPrompt) {
    long startNanos = System.nanoTime();
    if (!semanticCacheProperties.enabled()) {
      return logLookup(new CacheLookup.Skipped("disabled"), classifiedPrompt, null, startNanos);
    }
    if (classifiedPrompt.freshnessClass() != FreshnessClass.CACHEABLE) {
      return logLookup(new CacheLookup.Skipped("realTime"), classifiedPrompt, null, startNanos);
    }
    String tickerKey = tickerKey(classifiedPrompt.tickers());
    Embedding questionEmbedding;
    Optional<CacheCandidate> nearestCandidate;
    try {
      questionEmbedding = embeddingModel.embed(normalize(question)).content();
      nearestCandidate =
          semanticCacheStore.findNearest(questionEmbedding.vector(), tickerKey, clock.instant());
    } catch (RuntimeException lookupException) {
      log.atError()
          .addKeyValue("eventName", "semanticCacheLookupFailed")
          .addKeyValue("eventData", failureData(lookupException))
          .log("Semantic cache lookup failed; answering without the cache");
      return logLookup(new CacheLookup.Skipped("lookupFailed"), classifiedPrompt, null, startNanos);
    }
    Double bestSimilarity = nearestCandidate.map(CacheCandidate::similarity).orElse(null);
    CacheLookup cacheLookup =
        nearestCandidate
            .filter(
                candidate ->
                    candidate.similarity() >= semanticCacheProperties.similarityThreshold())
            .<CacheLookup>map(CacheLookup.Hit::new)
            .orElseGet(() -> new CacheLookup.Miss(question, questionEmbedding, tickerKey));
    return logLookup(cacheLookup, classifiedPrompt, bestSimilarity, startNanos);
  }

  /// Stores the Supervisor's answer after a miss when it is safe to reuse, else logs
  /// `semanticCacheStoreSkipped` with the `storeSkipReason`.
  ///
  /// @param cacheLookup the lookup made before the Supervisor ran; never a hit
  /// @param answer the Supervisor's answer
  /// @param runProvenance the agents, tools and tokens of the Supervisor run
  public void store(CacheLookup cacheLookup, String answer, RunProvenance runProvenance) {
    switch (cacheLookup) {
      case CacheLookup.Skipped skipped ->
          logStoreSkipped(Map.of("storeSkipReason", skipped.skipReason()));
      case CacheLookup.Hit _ ->
          throw new IllegalArgumentException("A cache hit runs no agents, so it has no answer");
      case CacheLookup.Miss miss -> storeAfterMiss(miss, answer, runProvenance);
    }
  }

  private void storeAfterMiss(CacheLookup.Miss miss, String answer, RunProvenance runProvenance) {
    List<AgentCall> agentCalls = runProvenance.agentCalls();
    List<String> portfolioToolsCalled =
        agentCalls.stream()
            .filter(
                agentCall ->
                    agentCall.agentName().equals(PortfolioAgentConfig.PORTFOLIO_AGENT_NAME))
            .flatMap(agentCall -> agentCall.toolExecutions().stream())
            .map(toolExecution -> toolExecution.request().name())
            .distinct()
            .toList();
    if (!portfolioToolsCalled.isEmpty()) {
      logStoreSkipped(
          Map.of(
              "storeSkipReason",
              "portfolioToolCalled",
              "portfolioToolsCalled",
              portfolioToolsCalled));
      return;
    }
    List<String> failedTools =
        agentCalls.stream()
            .flatMap(agentCall -> agentCall.toolExecutions().stream())
            .filter(ToolExecution::hasFailed)
            .map(toolExecution -> toolExecution.request().name())
            .distinct()
            .toList();
    if (!failedTools.isEmpty()) {
      logStoreSkipped(Map.of("storeSkipReason", "toolCallFailed", "failedTools", failedTools));
      return;
    }
    if (answer == null || answer.isBlank()) {
      logStoreSkipped(Map.of("storeSkipReason", "blankAnswer"));
      return;
    }
    long startNanos = System.nanoTime();
    long runTotalTokens = supervisorRunTokens(runProvenance);
    try {
      semanticCacheStore.save(
          new CacheEntry(
              miss.question(),
              miss.questionEmbedding().vector(),
              miss.tickerKey(),
              FreshnessClass.CACHEABLE,
              answer,
              runTotalTokens,
              clock.instant().plus(semanticCacheProperties.ttl())));
    } catch (RuntimeException storeException) {
      log.atError()
          .addKeyValue("eventName", "semanticCacheStoreFailed")
          .addKeyValue("eventData", failureData(storeException))
          .log("Semantic cache store failed; answer returned uncached");
      return;
    }
    log.atInfo()
        .addKeyValue("eventName", "semanticCacheStored")
        .addKeyValue(
            "eventData",
            Map.of(
                "tickerKey",
                miss.tickerKey(),
                "runTotalTokens",
                runTotalTokens,
                "cacheStoreDurationTimeMs",
                elapsedMs(startNanos)))
        .log("Answer stored in the semantic cache");
  }

  /// Returns the sorted, de-duplicated ticker set a stored answer is keyed by, e.g. `AAPL,MSFT`;
  /// empty when the prompt names no ticker.
  ///
  /// @param tickers the prompt's validated tickers
  /// @return the ticker key
  static String tickerKey(Collection<String> tickers) {
    return String.join(",", tickers.stream().distinct().sorted().toList());
  }

  /// Returns the text that is embedded for a question: trimmed and lowercased.
  ///
  /// @param question the question as asked
  /// @return the normalized question
  static String normalize(String question) {
    return question.strip().toLowerCase(Locale.ROOT);
  }

  /// Tokens of the Supervisor and its specialists, without the classifier, which runs on a hit too.
  private static long supervisorRunTokens(RunProvenance runProvenance) {
    return runProvenance.tokensByAgent().entrySet().stream()
        .filter(agentTokens -> !agentTokens.getKey().equals(PromptClassifierConfig.CLASSIFIER_NAME))
        .mapToLong(
            agentTokens ->
                agentTokens.getValue().inputTokens() + agentTokens.getValue().outputTokens())
        .sum();
  }

  private static CacheLookup logLookup(
      CacheLookup cacheLookup,
      ClassifiedPrompt classifiedPrompt,
      Double bestSimilarity,
      long startNanos) {
    Map<String, Object> lookupData = new LinkedHashMap<>();
    lookupData.put("cacheOutcome", cacheLookup.cacheOutcome());
    if (cacheLookup instanceof CacheLookup.Skipped skipped) {
      lookupData.put("skipReason", skipped.skipReason());
    }
    lookupData.put("freshnessClass", classifiedPrompt.freshnessClass());
    if (bestSimilarity != null) {
      lookupData.put("cacheSimilarity", bestSimilarity);
    }
    lookupData.put("tickers", classifiedPrompt.tickers());
    lookupData.put("cacheLookupDurationTimeMs", elapsedMs(startNanos));
    log.atInfo()
        .addKeyValue("eventName", "semanticCacheLookup")
        .addKeyValue("eventData", lookupData)
        .log("Semantic cache lookup");
    return cacheLookup;
  }

  private static void logStoreSkipped(Map<String, Object> skipData) {
    log.atInfo()
        .addKeyValue("eventName", "semanticCacheStoreSkipped")
        .addKeyValue("eventData", skipData)
        .log("Answer not stored in the semantic cache");
  }

  /// Event data of a failed lookup or store. The message is suppressed, since JDBC exceptions can
  /// embed SQL and bound values.
  private static Map<String, Object> failureData(RuntimeException cacheException) {
    return Map.of(
        "exceptionType", cacheException.getClass().getName(), "exceptionMessageSuppressed", true);
  }

  private static long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
