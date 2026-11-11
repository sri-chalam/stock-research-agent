package info.saagar.stockagent.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FakeQuoteProviderTest {

  private final FakeQuoteProvider fakeQuoteProvider = new FakeQuoteProvider();

  @Test
  void knowsAllSevenSeededSymbolsCaseInsensitively() {
    for (String tickerSymbol :
        new String[] {"AAPL", "msft", "NVDA", "TSLA", "AMZN", "GOOGL", "nflx"}) {
      assertThat(fakeQuoteProvider.findQuote(tickerSymbol)).isPresent();
    }
  }

  @Test
  void returnsEmptyForUnknownSymbol() {
    assertThat(fakeQuoteProvider.findQuote("XYZ")).isEmpty();
  }
}
