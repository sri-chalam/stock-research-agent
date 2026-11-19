package info.saagar.stockagent.guardrail;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Output guardrail on the Supervisor's answer (FR-34): removes every link except known source
/// links (sec.gov and Finnhub news), keeping the text around them, and adds the disclaimer once. It
/// rewrites the answer and never reprompts the model.
public class ResearchAnswerGuardrail implements OutputGuardrail {

  private static final Logger log = LoggerFactory.getLogger(ResearchAnswerGuardrail.class);

  /// Disclaimer every research answer ends with.
  public static final String DISCLAIMER = "Not investment advice.";

  /// The disclaimer without its full stop, which an answer may or may not end with.
  private static final String DISCLAIMER_WORDS = DISCLAIMER.substring(0, DISCLAIMER.length() - 1);

  /// An `http` or `https` link. It ends before whitespace, a bracket or a quote (so a Markdown
  /// link's text and target are separate links), and drops trailing sentence punctuation.
  private static final Pattern LINK_PATTERN =
      Pattern.compile("https?://[^\\s()\\[\\]<>\"']+?(?=[.,;:!?]*(?:[\\s()\\[\\]<>\"']|$))");

  /// Rewrites the answer so its only links are known source links and it ends with the disclaimer.
  ///
  /// @param responseFromLlm the Supervisor's answer
  /// @return success with the rewritten answer text
  @Override
  public OutputGuardrailResult validate(AiMessage responseFromLlm) {
    StringBuilder rewrittenAnswer = new StringBuilder();
    int removedLinkCount = 0;
    Matcher linkMatcher = LINK_PATTERN.matcher(responseFromLlm.text());
    while (linkMatcher.find()) {
      if (!isKnownSourceLink(linkMatcher.group())) {
        linkMatcher.appendReplacement(rewrittenAnswer, "");
        removedLinkCount++;
      }
    }
    linkMatcher.appendTail(rewrittenAnswer);
    boolean disclaimerAdded = !endsWithDisclaimer(rewrittenAnswer);
    if (disclaimerAdded) {
      rewrittenAnswer.append("\n\n").append(DISCLAIMER);
    }
    log.atInfo()
        .addKeyValue("eventName", "outputGuardrailRewrite")
        .addKeyValue(
            "eventData",
            Map.of("removedLinkCount", removedLinkCount, "disclaimerAdded", disclaimerAdded))
        .log("Output guardrail applied to the Supervisor answer");
    return successWith(rewrittenAnswer.toString());
  }

  /// Whether the answer already ends with the disclaimer, ignoring case, a final full stop and
  /// trailing whitespace. A mention earlier in the answer does not count.
  private static boolean endsWithDisclaimer(CharSequence answerText) {
    int answerEnd = answerText.length();
    while (answerEnd > 0 && Character.isWhitespace(answerText.charAt(answerEnd - 1))) {
      answerEnd--;
    }
    if (answerEnd > 0 && answerText.charAt(answerEnd - 1) == '.') {
      answerEnd--;
    }
    int disclaimerStart = answerEnd - DISCLAIMER_WORDS.length();
    return disclaimerStart >= 0
        && answerText
            .subSequence(disclaimerStart, answerEnd)
            .toString()
            .equalsIgnoreCase(DISCLAIMER_WORDS);
  }

  /// A link to sec.gov (any subdomain) or to a Finnhub news article (`finnhub.io/api/news?…`). The
  /// whole authority is compared, so a link with a port or user info is not a known source, and a
  /// link with a second `://` glued on is not either.
  private static boolean isKnownSourceLink(String link) {
    int schemeEnd = link.indexOf("://") + 3;
    if (link.indexOf("://", schemeEnd) >= 0) {
      return false;
    }
    String afterScheme = link.substring(schemeEnd).toLowerCase(Locale.ROOT);
    int authorityEnd = indexOfAuthorityEnd(afterScheme);
    String authority = afterScheme.substring(0, authorityEnd);
    if (authority.equals("sec.gov") || authority.endsWith(".sec.gov")) {
      return true;
    }
    return authority.equals("finnhub.io") && afterScheme.startsWith("/api/news?", authorityEnd);
  }

  private static int indexOfAuthorityEnd(String afterScheme) {
    for (int charIndex = 0; charIndex < afterScheme.length(); charIndex++) {
      char linkChar = afterScheme.charAt(charIndex);
      if (linkChar == '/' || linkChar == '?' || linkChar == '#') {
        return charIndex;
      }
    }
    return afterScheme.length();
  }
}
