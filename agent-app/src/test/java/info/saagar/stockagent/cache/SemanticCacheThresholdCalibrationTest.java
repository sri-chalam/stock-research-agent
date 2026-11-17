package info.saagar.stockagent.cache;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.CosineSimilarity;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/// Checks the configured `similarity-threshold` against hand-written question pairs scored by the
/// real bge-small model: paraphrases must reach it and different questions about the same ticker
/// must stay below it. Each pair's score is logged as `calibrationPairScored`.
///
/// Observed scores: the should-hit pairs score 0.890 to 0.934 and the should-miss pairs 0.655 to
/// 0.848, so the threshold is 0.87. The two known-error groups overlap that gap from both sides, so
/// no threshold separates every paraphrase from every different question.
class SemanticCacheThresholdCalibrationTest {

  private static final Logger log =
      LoggerFactory.getLogger(SemanticCacheThresholdCalibrationTest.class);

  private static final EmbeddingModel EMBEDDING_MODEL = new BgeSmallEnV15QuantizedEmbeddingModel();

  private static double configuredThreshold;

  /// A pair of questions with the same ticker set.
  record QuestionPair(String firstQuestion, String secondQuestion) {
    @Override
    public String toString() {
      return "\"" + firstQuestion + "\" / \"" + secondQuestion + "\"";
    }
  }

  @BeforeAll
  static void readConfiguredThreshold() throws IOException {
    List<PropertySource<?>> applicationYml =
        new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"));
    configuredThreshold =
        Double.parseDouble(
            String.valueOf(
                applicationYml
                    .getFirst()
                    .getProperty("stock-agent.semantic-cache.similarity-threshold")));
  }

  /// Paraphrases that must hit. The first is the Iteration 4 acceptance pair.
  static List<QuestionPair> shouldHitPairs() {
    return List.of(
        new QuestionPair(
            "What risks does Apple list in its latest 10-K?",
            "What are Apple's 10-K risk factors?"),
        new QuestionPair(
            "Summarize the latest news about Microsoft", "What is the recent news on Microsoft?"),
        new QuestionPair("Recent news about Tesla", "What's the latest Tesla news?"),
        new QuestionPair(
            "What did Apple's last 10-K say about supply chain risk?",
            "Supply chain risks in Apple's latest 10-K"));
  }

  /// Different questions about the same ticker that must miss.
  static List<QuestionPair> shouldMissPairs() {
    return List.of(
        new QuestionPair("AAPL revenue trend", "AAPL latest news"),
        new QuestionPair("Apple's risk factors", "Apple's revenue"),
        new QuestionPair("What does Tesla do?", "When is Tesla's next earnings date?"),
        new QuestionPair(
            "Summarize the latest news about Microsoft", "List Microsoft's recent 10-K filings"));
  }

  /// Paraphrases bge-small scores below the threshold. A false miss costs one Supervisor run. The
  /// model cannot tell that `AAPL` is Apple, so a ticker in one question and a company name in the
  /// other always miss.
  static List<QuestionPair> knownFalseMissPairs() {
    return List.of(
        new QuestionPair("AAPL risk factors?", "What risks does Apple list in its 10-K?"),
        new QuestionPair("What does Nvidia do?", "Describe Nvidia's business"),
        new QuestionPair(
            "What are the risk factors in Microsoft's 10-K?",
            "What risks does Microsoft's annual report list?"));
  }

  /// Different questions bge-small scores at or above the threshold: the same sentence with one
  /// metric or form type swapped. A false hit returns the other question's answer; no threshold
  /// that lets the paraphrases above hit keeps these out.
  static List<QuestionPair> knownFalseHitPairs() {
    return List.of(
        new QuestionPair(
            "Apple revenue for the last 3 years", "Apple net income for the last 3 years"),
        new QuestionPair(
            "What are Apple's 10-K risk factors?", "What are Apple's 10-Q risk factors?"),
        new QuestionPair("Apple's gross margin trend", "Apple's net income trend"));
  }

  @ParameterizedTest
  @MethodSource("shouldHitPairs")
  void paraphraseReachesTheThreshold(QuestionPair questionPair) {
    assertThat(similarity(questionPair)).isGreaterThanOrEqualTo(configuredThreshold);
  }

  @ParameterizedTest
  @MethodSource("shouldMissPairs")
  void differentQuestionStaysBelowTheThreshold(QuestionPair questionPair) {
    assertThat(similarity(questionPair)).isLessThan(configuredThreshold);
  }

  /// Fails when a model or threshold change starts matching these, so the list stays accurate.
  @ParameterizedTest
  @MethodSource("knownFalseMissPairs")
  void knownFalseMissStaysBelowTheThreshold(QuestionPair questionPair) {
    assertThat(similarity(questionPair)).isLessThan(configuredThreshold);
  }

  /// Fails when a model or threshold change stops matching these, so the list stays accurate.
  @ParameterizedTest
  @MethodSource("knownFalseHitPairs")
  void knownFalseHitReachesTheThreshold(QuestionPair questionPair) {
    assertThat(similarity(questionPair)).isGreaterThanOrEqualTo(configuredThreshold);
  }

  private static double similarity(QuestionPair questionPair) {
    Embedding firstEmbedding =
        EMBEDDING_MODEL.embed(SemanticCache.normalize(questionPair.firstQuestion())).content();
    Embedding secondEmbedding =
        EMBEDDING_MODEL.embed(SemanticCache.normalize(questionPair.secondQuestion())).content();
    double pairSimilarity = CosineSimilarity.between(firstEmbedding, secondEmbedding);
    log.atInfo()
        .addKeyValue("eventName", "calibrationPairScored")
        .addKeyValue(
            "eventData",
            Map.of(
                "firstQuestion",
                questionPair.firstQuestion(),
                "secondQuestion",
                questionPair.secondQuestion(),
                "cacheSimilarity",
                pairSimilarity,
                "similarityThreshold",
                configuredThreshold))
        .log("Calibration pair scored");
    return pairSimilarity;
  }
}
