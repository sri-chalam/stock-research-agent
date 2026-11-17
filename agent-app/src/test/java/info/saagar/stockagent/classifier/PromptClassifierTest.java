package info.saagar.stockagent.classifier;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import info.saagar.stockagent.config.ClassifierProperties;
import info.saagar.stockagent.config.PromptClassifierConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Drives the prompt classifier built by [PromptClassifierConfig] with a fake chat model.
class PromptClassifierTest {

  private static final ClassifierProperties CLASSIFIER_PROPERTIES =
      new ClassifierProperties("claude-haiku-5-5", "classifier-v1", 256, Duration.ofSeconds(10));

  /// Answers every call with one canned reply, or throws when no reply is given, and records the
  /// requests it received.
  private static final class FakeClassifierChatModel implements ChatModel {

    private final String replyJson;
    private final List<ChatRequest> receivedRequests = new ArrayList<>();

    private FakeClassifierChatModel(String replyJson) {
      this.replyJson = replyJson;
    }

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
      receivedRequests.add(chatRequest);
      if (replyJson == null) {
        throw new IllegalStateException("Classifier model unavailable");
      }
      return ChatResponse.builder()
          .aiMessage(AiMessage.from(replyJson))
          .modelName("claude-haiku-5-5")
          .tokenUsage(new TokenUsage(120, 15))
          .build();
    }
  }

  private static PromptClassifier classifierReplying(FakeClassifierChatModel fakeChatModel)
      throws Exception {
    return new PromptClassifierConfig().promptClassifier(fakeChatModel, CLASSIFIER_PROPERTIES);
  }

  @Test
  void realTimePromptKeepsItsClassAndTickers() throws Exception {
    FakeClassifierChatModel fakeChatModel =
        new FakeClassifierChatModel("{\"freshness\":\"REAL_TIME\",\"tickers\":[\"AAPL\"]}");

    ClassifiedPrompt classifiedPrompt =
        classifierReplying(fakeChatModel).classify("Price of Apple");

    assertThat(classifiedPrompt.freshnessClass()).isEqualTo(FreshnessClass.REAL_TIME);
    assertThat(classifiedPrompt.tickers()).containsExactly("AAPL");
  }

  @Test
  void classifierCallSendsTheVersionedPromptAndTheUserPrompt() throws Exception {
    FakeClassifierChatModel fakeChatModel =
        new FakeClassifierChatModel("{\"freshness\":\"CACHEABLE\",\"tickers\":[]}");

    classifierReplying(fakeChatModel).classify("What is a 10-K?");

    List<ChatMessage> sentMessages = fakeChatModel.receivedRequests.getFirst().messages();
    assertThat(sentMessages.getFirst())
        .isInstanceOfSatisfying(
            SystemMessage.class,
            systemMessage -> assertThat(systemMessage.text()).contains("prompt classifier"));
    assertThat(sentMessages.getLast().toString()).contains("What is a 10-K?");
  }

  @Test
  void tickersAreUppercasedAndAllKept() throws Exception {
    FakeClassifierChatModel fakeChatModel =
        new FakeClassifierChatModel(
            "{\"freshness\":\"CACHEABLE\",\"tickers\":[\"aapl\",\"Msft\"]}");

    ClassifiedPrompt classifiedPrompt =
        classifierReplying(fakeChatModel).classify("Compare Apple and Microsoft revenue");

    assertThat(classifiedPrompt.freshnessClass()).isEqualTo(FreshnessClass.CACHEABLE);
    assertThat(classifiedPrompt.tickers()).containsExactly("AAPL", "MSFT");
  }

  @Test
  void successfulClassificationCarriesTheModelResponseForTokensAndModelName() throws Exception {
    FakeClassifierChatModel fakeChatModel =
        new FakeClassifierChatModel("{\"freshness\":\"CACHEABLE\",\"tickers\":[\"AAPL\"]}");

    ClassifiedPrompt classifiedPrompt =
        classifierReplying(fakeChatModel).classify("AAPL risk factors");

    assertThat(classifiedPrompt.classifierResponse().modelName()).isEqualTo("claude-haiku-5-5");
    assertThat(classifiedPrompt.classifierResponse().tokenUsage())
        .isEqualTo(new TokenUsage(120, 15));
  }

  @Test
  void classifierFailureFallsBackToRealTimeWithNoTickers() throws Exception {
    FakeClassifierChatModel failingChatModel = new FakeClassifierChatModel(null);

    ClassifiedPrompt classifiedPrompt =
        classifierReplying(failingChatModel).classify("AAPL risk factors");

    assertThat(classifiedPrompt.freshnessClass()).isEqualTo(FreshnessClass.REAL_TIME);
    assertThat(classifiedPrompt.tickers()).isEmpty();
    assertThat(classifiedPrompt.classifierResponse()).isNull();
  }

  @Test
  void invalidTickerFallsBackToRealTimeButKeepsTheModelResponse() throws Exception {
    FakeClassifierChatModel fakeChatModel =
        new FakeClassifierChatModel(
            "{\"freshness\":\"CACHEABLE\",\"tickers\":[\"AAPL\",\"APPLE1\"]}");

    ClassifiedPrompt classifiedPrompt =
        classifierReplying(fakeChatModel).classify("Apple risk factors");

    assertThat(classifiedPrompt.freshnessClass()).isEqualTo(FreshnessClass.REAL_TIME);
    assertThat(classifiedPrompt.tickers()).isEmpty();
    assertThat(classifiedPrompt.classifierResponse().tokenUsage())
        .isEqualTo(new TokenUsage(120, 15));
  }

  @Test
  void missingFreshnessFallsBackToRealTime() throws Exception {
    FakeClassifierChatModel fakeChatModel = new FakeClassifierChatModel("{\"tickers\":[\"AAPL\"]}");

    ClassifiedPrompt classifiedPrompt =
        classifierReplying(fakeChatModel).classify("Apple risk factors");

    assertThat(classifiedPrompt.freshnessClass()).isEqualTo(FreshnessClass.REAL_TIME);
    assertThat(classifiedPrompt.tickers()).isEmpty();
  }
}
