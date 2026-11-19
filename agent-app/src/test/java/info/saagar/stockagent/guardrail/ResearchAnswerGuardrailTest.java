package info.saagar.stockagent.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.langchain4j.data.message.AiMessage;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ResearchAnswerGuardrailTest {

  private final ResearchAnswerGuardrail researchAnswerGuardrail = new ResearchAnswerGuardrail();

  private String guard(String answerText) {
    return researchAnswerGuardrail.validate(AiMessage.from(answerText)).successfulText();
  }

  @Test
  void disclaimerIsAppendedToAnAnswerWithoutOne() {
    assertThat(guard("Apple's revenue was $391B in FY2024."))
        .isEqualTo("Apple's revenue was $391B in FY2024.\n\nNot investment advice.");
  }

  @Test
  void disclaimerTheModelAlreadyWroteIsNotDuplicated() {
    String answerWithDisclaimer = "AAPL trades at $189.42. This is not investment advice.";

    assertThat(guard(answerWithDisclaimer)).isEqualTo(answerWithDisclaimer);
  }

  @Test
  void otherLinksAreRemovedAndTheSurroundingTextIsKept() {
    String answerText =
        "Shares rose (see https://evil.example.com/pump?id=1). Details at"
            + " http://blog.example.org/nvda, per analysts. Not investment advice.";

    assertThat(guard(answerText))
        .isEqualTo("Shares rose (see ). Details at , per analysts. Not investment advice.");
  }

  @Test
  void secGovAndFinnhubNewsLinksAreKept() {
    String answerText =
        "10-K: https://www.sec.gov/Archives/edgar/data/789019/000095017025100235/msft.htm."
            + " Facts: https://data.sec.gov/api/xbrl/companyfacts/CIK0000320193.json"
            + " News: https://finnhub.io/api/news?id=4f1c2b. Not investment advice.";

    assertThat(guard(answerText)).isEqualTo(answerText);
  }

  @Test
  void lookalikeHostsAndOtherFinnhubPagesAreRemoved() {
    String answerText =
        "A https://sec.gov.evil.example/10k B https://evilsec.gov/x"
            + " C https://finnhub.io/pricing D. Not investment advice.";

    assertThat(guard(answerText)).isEqualTo("A  B  C  D. Not investment advice.");
  }

  @Test
  void disclaimerMentionedMidAnswerIsStillAddedAtTheEnd() {
    assertThat(guard("This is not investment advice, but margins fell 3%."))
        .isEqualTo("This is not investment advice, but margins fell 3%.\n\nNot investment advice.");
  }

  @Test
  void markdownLinkTargetIsRemovedEvenWhenItsTextIsAKnownSourceLink() {
    assertThat(
            guard(
                "[Reuters](https://evil.example.com) and"
                    + " [https://www.sec.gov/x.htm](https://evil.example.com/y)."
                    + " Not investment advice."))
        .isEqualTo("[Reuters]() and [https://www.sec.gov/x.htm](). Not investment advice.");
  }

  @Test
  void linkGluedToAKnownSourceLinkIsRemovedWithIt() {
    assertThat(
            guard("See https://www.sec.gov/a,https://evil.example.com now. Not investment advice."))
        .isEqualTo("See  now. Not investment advice.");
  }

  @Test
  void finnhubPathThatOnlyStartsLikeTheNewsPathIsRemoved() {
    assertThat(guard("X https://finnhub.io/api/newsletter Y. Not investment advice."))
        .isEqualTo("X  Y. Not investment advice.");
  }

  @Test
  void rewriteIsLoggedWithTheRemovedLinkCountAndNoUrls() {
    Logger guardrailLogger = (Logger) LoggerFactory.getLogger(ResearchAnswerGuardrail.class);
    ListAppender<ILoggingEvent> capturedEvents = new ListAppender<>();
    capturedEvents.start();
    guardrailLogger.addAppender(capturedEvents);
    try {
      guard(
          "See https://evil.example.com/a and https://other.example.net/b,"
              + " and https://www.sec.gov/Archives/x.htm");
    } finally {
      guardrailLogger.detachAppender(capturedEvents);
    }

    assertThat(capturedEvents.list).hasSize(1);
    Map<String, Object> keyValues = new HashMap<>();
    capturedEvents
        .list
        .getFirst()
        .getKeyValuePairs()
        .forEach(keyValuePair -> keyValues.put(keyValuePair.key, keyValuePair.value));
    assertThat(keyValues).containsEntry("eventName", "outputGuardrailRewrite");
    assertThat(keyValues.get("eventData"))
        .isEqualTo(Map.of("removedLinkCount", 2, "disclaimerAdded", true));
    assertThat(keyValues.toString()).doesNotContain("example", "sec.gov");
  }
}
