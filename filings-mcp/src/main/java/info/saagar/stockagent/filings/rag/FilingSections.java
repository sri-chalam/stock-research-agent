package info.saagar.stockagent.filings.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.transformer.jsoup.HtmlToTextDocumentTransformer;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Extracts a narrative section from a filing's primary HTML document.
///
/// Parsing is deliberately thin (AGENTS.md §1): heading regexes over the plain text, with no
/// handling for unusual layouts. EDGAR filings put headings in `div`s, which the jsoup transformer
/// does not break into lines, so headings are matched anywhere in the text, not at line starts.
public final class FilingSections {

  /// `section` metadata value, and citation label, of the risk-factors section.
  public static final String RISK_FACTORS_SECTION = "Item 1A";

  /// `Item 1A. Risk Factors`; a cross-reference such as "Item 1A of this Form 10-K" does not match.
  private static final Pattern RISK_FACTORS_HEADING =
      Pattern.compile(
          "\\bitem\\s+1a\\.?\\s*[:.\\-–—]?\\s*risk\\s+factors", Pattern.CASE_INSENSITIVE);

  /// The next Item heading, e.g. `Item 1B.` or `Item 7.`. The period keeps cross-references such as
  /// "Item 7 of this Form 10-K" from ending the section.
  private static final Pattern NEXT_ITEM_HEADING =
      Pattern.compile("\\bitem\\s+\\d+[a-z]?\\.", Pattern.CASE_INSENSITIVE);

  private static final HtmlToTextDocumentTransformer HTML_TO_TEXT =
      new HtmlToTextDocumentTransformer();

  private FilingSections() {}

  /// Returns the text of Item 1A (Risk Factors): from its heading up to the next Item heading.
  ///
  /// The heading also appears in the table of contents, so the occurrence followed by the most text
  /// wins.
  ///
  /// @param filingHtml the filing's primary document
  /// @return the section text without its heading; empty when the filing has no Item 1A heading
  public static Optional<String> riskFactors(String filingHtml) {
    String filingText = HTML_TO_TEXT.transform(Document.from(filingHtml)).text().replace(' ', ' ');
    Matcher headingMatcher = RISK_FACTORS_HEADING.matcher(filingText);
    Matcher nextItemMatcher = NEXT_ITEM_HEADING.matcher(filingText);
    String longestSectionText = null;
    while (headingMatcher.find()) {
      int sectionStart = headingMatcher.end();
      int sectionEnd =
          nextItemMatcher.find(sectionStart) ? nextItemMatcher.start() : filingText.length();
      String sectionText = filingText.substring(sectionStart, sectionEnd).strip();
      if (longestSectionText == null || sectionText.length() > longestSectionText.length()) {
        longestSectionText = sectionText;
      }
    }
    return Optional.ofNullable(longestSectionText).filter(sectionText -> !sectionText.isEmpty());
  }
}
