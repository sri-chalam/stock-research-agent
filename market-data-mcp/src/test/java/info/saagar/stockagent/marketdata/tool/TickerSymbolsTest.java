package info.saagar.stockagent.marketdata.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TickerSymbolsTest {

  @ParameterizedTest
  @CsvSource({"AAPL, AAPL", "aapl, AAPL", "' msft ', MSFT", "BRK.B, BRK.B", "F, F"})
  void acceptsUsTickersAndNormalisesThem(String rawSymbol, String expectedSymbol) {
    assertThat(TickerSymbols.normalize(rawSymbol)).isEqualTo(expectedSymbol);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"TOOLONG", "AAPL1", "RY.TO", "AAPL;DROP", "$AAPL", "Apple Inc"})
  void rejectsAnythingElseWithReadableMessage(String rawSymbol) {
    assertThatThrownBy(() -> TickerSymbols.normalize(rawSymbol))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("Invalid ticker symbol")
        .hasMessageEndingWith("Provide a US ticker such as AAPL.");
  }
}
