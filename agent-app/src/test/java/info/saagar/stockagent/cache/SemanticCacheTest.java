package info.saagar.stockagent.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolExecutionResult;
import info.saagar.stockagent.classifier.ClassifiedPrompt;
import info.saagar.stockagent.classifier.FreshnessClass;
import info.saagar.stockagent.classifier.InputGuardrailVerdict;
import info.saagar.stockagent.config.FilingsAgentConfig;
import info.saagar.stockagent.config.PortfolioAgentConfig;
import info.saagar.stockagent.config.PromptClassifierConfig;
import info.saagar.stockagent.config.SemanticCacheProperties;
import info.saagar.stockagent.config.SupervisorAgentConfig;
import info.saagar.stockagent.logging.RunProvenance;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Lookup and store rules of [SemanticCache], over the in-memory store and the real bge-small
/// model.
class SemanticCacheTest {

  private static final EmbeddingModel EMBEDDING_MODEL = new BgeSmallEnV15QuantizedEmbeddingModel();

  private static final String RISK_QUESTION = "What risks does Apple list in its latest 10-K?";
  private static final String REWORDED_RISK_QUESTION = "What are Apple's 10-K risk factors?";
  private static final String RISK_ANSWER = "Apple lists supply-chain and regulatory risks.";
  private static final double THRESHOLD = 0.87;
  private static final Duration TTL = Duration.ofHours(1);

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-17T12:00:00Z"));

  /// A clock that tests move forward.
  static final class MutableClock extends Clock {
    private Instant currentInstant;

    MutableClock(Instant startInstant) {
      this.currentInstant = startInstant;
    }

    void advance(Duration elapsed) {
      currentInstant = currentInstant.plus(elapsed);
    }

    @Override
    public Instant instant() {
      return currentInstant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  private SemanticCache semanticCache(boolean enabled) {
    return new SemanticCache(
        EMBEDDING_MODEL,
        new InMemorySemanticCacheStore(),
        new SemanticCacheProperties(enabled, THRESHOLD, TTL, "in-memory", null),
        clock);
  }

  private static ClassifiedPrompt cacheable(String... tickers) {
    return new ClassifiedPrompt(
        FreshnessClass.CACHEABLE, List.of(tickers), InputGuardrailVerdict.PASSED, null);
  }

  /// A run in which the Filings analyst called `listFilings` once.
  private static RunProvenance filingsRun() {
    return runWithTool(FilingsAgentConfig.FILINGS_AGENT_NAME, "listFilings", false);
  }

  private static RunProvenance runWithTool(String agentName, String toolName, boolean toolFailed) {
    RunProvenance runProvenance = new RunProvenance();
    runProvenance.tokensUsed(SupervisorAgentConfig.SUPERVISOR_AGENT_NAME, new TokenUsage(300, 40));
    runProvenance.agentInvoked(agentName);
    runProvenance.tokensUsed(agentName, new TokenUsage(200, 20));
    runProvenance.toolExecuted(
        agentName,
        ToolExecution.builder()
            .request(ToolExecutionRequest.builder().id("call-1").name(toolName).build())
            .result(ToolExecutionResult.builder().isError(toolFailed).resultText("result").build())
            .invocationContext(InvocationContext.builder().build())
            .build());
    return runProvenance;
  }

  /// Looks the question up and, on a miss, stores the answer as the Supervisor would.
  private static CacheLookup askAndStore(
      SemanticCache semanticCache,
      String question,
      ClassifiedPrompt classifiedPrompt,
      String answer,
      RunProvenance runProvenance) {
    CacheLookup cacheLookup = semanticCache.lookup(question, classifiedPrompt);
    if (!(cacheLookup instanceof CacheLookup.Hit)) {
      semanticCache.store(cacheLookup, answer, runProvenance);
    }
    return cacheLookup;
  }

  @Test
  void rewordedQuestionWithTheSameTickersHitsTheStoredAnswer() {
    SemanticCache semanticCache = semanticCache(true);

    CacheLookup firstLookup =
        askAndStore(semanticCache, RISK_QUESTION, cacheable("AAPL"), RISK_ANSWER, filingsRun());
    CacheLookup secondLookup = semanticCache.lookup(REWORDED_RISK_QUESTION, cacheable("AAPL"));

    assertThat(firstLookup).isInstanceOf(CacheLookup.Miss.class);
    assertThat(secondLookup)
        .isInstanceOfSatisfying(
            CacheLookup.Hit.class,
            hit -> {
              assertThat(hit.cachedAnswer().answer()).isEqualTo(RISK_ANSWER);
              assertThat(hit.cachedAnswer().similarity()).isGreaterThanOrEqualTo(THRESHOLD);
            });
  }

  @Test
  void tokensSavedAreTheSupervisorRunsTokensWithoutTheClassifiers() {
    SemanticCache semanticCache = semanticCache(true);
    RunProvenance runProvenance = filingsRun();
    runProvenance.tokensUsed(PromptClassifierConfig.CLASSIFIER_NAME, new TokenUsage(100, 10));

    askAndStore(semanticCache, RISK_QUESTION, cacheable("AAPL"), RISK_ANSWER, runProvenance);
    CacheLookup secondLookup = semanticCache.lookup(RISK_QUESTION, cacheable("AAPL"));

    assertThat(secondLookup)
        .isInstanceOfSatisfying(
            CacheLookup.Hit.class,
            hit -> assertThat(hit.cachedAnswer().runTotalTokens()).isEqualTo(300 + 40 + 200 + 20));
  }

  @Test
  void questionIsNormalizedBeforeEmbedding() {
    SemanticCache semanticCache = semanticCache(true);

    askAndStore(semanticCache, RISK_QUESTION, cacheable("AAPL"), RISK_ANSWER, filingsRun());
    CacheLookup secondLookup =
        semanticCache.lookup(
            "  " + RISK_QUESTION.toUpperCase(java.util.Locale.ROOT) + "  ", cacheable("AAPL"));

    assertThat(secondLookup)
        .isInstanceOfSatisfying(
            CacheLookup.Hit.class,
            hit -> assertThat(hit.cachedAnswer().similarity()).isCloseTo(1.0, within(1e-6)));
  }

  @Test
  void sameQuestionWithADifferentTickerMisses() {
    SemanticCache semanticCache = semanticCache(true);

    askAndStore(semanticCache, "AAPL risk factors", cacheable("AAPL"), RISK_ANSWER, filingsRun());
    CacheLookup secondLookup = semanticCache.lookup("MSFT risk factors", cacheable("MSFT"));

    assertThat(secondLookup).isInstanceOf(CacheLookup.Miss.class);
  }

  @Test
  void tickerSetMustMatchExactlyInAnyOrder() {
    SemanticCache semanticCache = semanticCache(true);
    String question = "Compare Apple and Microsoft risk factors";

    askAndStore(semanticCache, question, cacheable("MSFT", "AAPL"), RISK_ANSWER, filingsRun());

    assertThat(semanticCache.lookup(question, cacheable("AAPL", "MSFT")))
        .isInstanceOf(CacheLookup.Hit.class);
    assertThat(semanticCache.lookup(question, cacheable("AAPL")))
        .isInstanceOf(CacheLookup.Miss.class);
  }

  @Test
  void emptyTickerSetMatchesOnlyAnEmptyTickerSet() {
    SemanticCache semanticCache = semanticCache(true);
    String question = "What is in the general market news today?";

    askAndStore(semanticCache, question, cacheable(), "Markets were quiet.", filingsRun());

    assertThat(semanticCache.lookup(question, cacheable())).isInstanceOf(CacheLookup.Hit.class);
    assertThat(semanticCache.lookup(question, cacheable("AAPL")))
        .isInstanceOf(CacheLookup.Miss.class);
  }

  @Test
  void storedAnswerMissesOnceTheTtlHasPassed() {
    SemanticCache semanticCache = semanticCache(true);

    askAndStore(semanticCache, RISK_QUESTION, cacheable("AAPL"), RISK_ANSWER, filingsRun());
    clock.advance(TTL.minusSeconds(1));
    CacheLookup beforeExpiry = semanticCache.lookup(RISK_QUESTION, cacheable("AAPL"));
    clock.advance(Duration.ofSeconds(1));
    CacheLookup atExpiry = semanticCache.lookup(RISK_QUESTION, cacheable("AAPL"));

    assertThat(beforeExpiry).isInstanceOf(CacheLookup.Hit.class);
    assertThat(atExpiry).isInstanceOf(CacheLookup.Miss.class);
  }

  @Test
  void questionBelowTheThresholdMisses() {
    SemanticCache semanticCache = semanticCache(true);

    askAndStore(semanticCache, "AAPL revenue trend", cacheable("AAPL"), "Up.", filingsRun());
    CacheLookup secondLookup = semanticCache.lookup("AAPL latest news", cacheable("AAPL"));

    assertThat(secondLookup).isInstanceOf(CacheLookup.Miss.class);
  }

  @Test
  void realTimePromptSkipsTheLookupAndIsNeverStored() {
    SemanticCache semanticCache = semanticCache(true);
    ClassifiedPrompt realTimePrompt =
        new ClassifiedPrompt(
            FreshnessClass.REAL_TIME, List.of("AAPL"), InputGuardrailVerdict.PASSED, null);

    CacheLookup firstLookup =
        askAndStore(semanticCache, "Price of Apple", realTimePrompt, "$189.42", filingsRun());
    CacheLookup secondLookup = semanticCache.lookup("Price of Apple", cacheable("AAPL"));

    assertThat(firstLookup).isEqualTo(new CacheLookup.Skipped("realTime"));
    assertThat(secondLookup).isInstanceOf(CacheLookup.Miss.class);
  }

  @Test
  void disabledCacheSkipsEveryLookupAndStore() {
    SemanticCache disabledCache = semanticCache(false);

    CacheLookup firstLookup =
        askAndStore(disabledCache, RISK_QUESTION, cacheable("AAPL"), RISK_ANSWER, filingsRun());
    CacheLookup secondLookup = disabledCache.lookup(RISK_QUESTION, cacheable("AAPL"));

    assertThat(firstLookup).isEqualTo(new CacheLookup.Skipped("disabled"));
    assertThat(secondLookup).isEqualTo(new CacheLookup.Skipped("disabled"));
  }

  @Test
  void runThatCalledAPortfolioToolIsNotStored() {
    SemanticCache semanticCache = semanticCache(true);
    String holdingsQuestion = "What stocks do I hold?";

    askAndStore(
        semanticCache,
        holdingsQuestion,
        cacheable(),
        "You hold AAPL.",
        runWithTool(PortfolioAgentConfig.PORTFOLIO_AGENT_NAME, "getHoldings", false));

    assertThat(semanticCache.lookup(holdingsQuestion, cacheable()))
        .isInstanceOf(CacheLookup.Miss.class);
  }

  @Test
  void runWithAFailedToolCallIsNotStored() {
    SemanticCache semanticCache = semanticCache(true);

    askAndStore(
        semanticCache,
        RISK_QUESTION,
        cacheable("AAPL"),
        RISK_ANSWER,
        runWithTool(FilingsAgentConfig.FILINGS_AGENT_NAME, "searchFilings", true));

    assertThat(semanticCache.lookup(RISK_QUESTION, cacheable("AAPL")))
        .isInstanceOf(CacheLookup.Miss.class);
  }

  @Test
  void blankAnswerIsNotStored() {
    SemanticCache semanticCache = semanticCache(true);

    askAndStore(semanticCache, RISK_QUESTION, cacheable("AAPL"), "  ", filingsRun());

    assertThat(semanticCache.lookup(RISK_QUESTION, cacheable("AAPL")))
        .isInstanceOf(CacheLookup.Miss.class);
  }
}
