package info.saagar.stockagent.filings.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FilingSectionsTest {

  @Test
  void extractsTheItem1ABodyNotTheTableOfContentsLine() {
    String riskFactors =
        FilingSections.riskFactors(
                SyntheticFilings.tenKHtml("Competition for synthetic widgets is intense."))
            .orElseThrow();

    assertThat(riskFactors)
        .contains(SyntheticFilings.SUPPLY_CHAIN_RISK)
        .contains("Competition for synthetic widgets is intense.")
        .doesNotContain("Unresolved Staff Comments")
        .doesNotContain("Business")
        .doesNotContain(SyntheticFilings.MANAGEMENT_DISCUSSION);
  }

  @Test
  void isEmptyWhenTheFilingHasNoItem1A() {
    assertThat(
            FilingSections.riskFactors(
                "<html><body><div>Item 1.&#160;Business</div><div>Widgets.</div></body></html>"))
        .isEmpty();
  }
}
