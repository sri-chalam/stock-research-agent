package info.saagar.stockagent.filings.rag;

/// A synthetic 10-K primary document, laid out like an EDGAR inline-XBRL filing: a table of
/// contents in a table, then each Item heading and its paragraphs in `div`s.
final class SyntheticFilings {

  /// A risk-factors paragraph that appears only in the body of Item 1A.
  static final String SUPPLY_CHAIN_RISK =
      "The Company depends on component suppliers and contract manufacturers in Asia, and a"
          + " disruption at any of them could delay shipments of its synthetic widgets.";

  /// A paragraph of Item 7, which follows Item 1A and must not be extracted with it.
  static final String MANAGEMENT_DISCUSSION =
      "Net sales of synthetic widgets increased during the fiscal year.";

  private SyntheticFilings() {}

  /// Returns a 10-K whose Item 1A body holds `riskFactorParagraphs` after the supply-chain risk.
  ///
  /// @param riskFactorParagraphs extra Item 1A paragraphs, e.g. to make the section long
  /// @return the filing HTML
  static String tenKHtml(String... riskFactorParagraphs) {
    StringBuilder riskFactorDivs = new StringBuilder();
    riskFactorDivs.append("<div><span>").append(SUPPLY_CHAIN_RISK).append("</span></div>");
    for (String riskFactorParagraph : riskFactorParagraphs) {
      riskFactorDivs.append("<div><span>").append(riskFactorParagraph).append("</span></div>");
    }
    return """
        <html><body>
        <div><span>SYNTHETIC WIDGETS INC.</span></div>
        <div><span>Form 10-K</span></div>
        <table>
          <tr><td>Item 1.</td><td>Business</td><td>1</td></tr>
          <tr><td>Item 1A.</td><td>Risk Factors</td><td>5</td></tr>
          <tr><td>Item 1B.</td><td>Unresolved Staff Comments</td><td>17</td></tr>
          <tr><td>Item 7.</td><td>Management&#8217;s Discussion and Analysis</td><td>20</td></tr>
        </table>
        <div><span>PART I</span></div>
        <div><span>Item 1.&#160;&#160;&#160;&#160;Business</span></div>
        <div><span>The Company designs synthetic widgets. See Part I, Item 1A of this Form 10-K\
         under the heading &#8220;Risk Factors.&#8221;</span></div>
        <div><span>Item 1A.&#160;&#160;&#160;&#160;Risk Factors</span></div>
        %s
        <div><span>Item 1B.&#160;&#160;&#160;&#160;Unresolved Staff Comments</span></div>
        <div><span>None.</span></div>
        <div><span>Item 7.&#160;&#160;&#160;&#160;Management&#8217;s Discussion and Analysis\
        </span></div>
        <div><span>%s</span></div>
        </body></html>
        """
        .formatted(riskFactorDivs, MANAGEMENT_DISCUSSION);
  }
}
