package info.saagar.stockagent.marketdata.provider;

/// The market news categories the tools support (MCP-2).
public enum MarketNewsCategory {
  /// General market news.
  GENERAL("general"),
  /// Merger and acquisition news.
  MERGER("merger");

  private final String finnhubCategory;

  MarketNewsCategory(String finnhubCategory) {
    this.finnhubCategory = finnhubCategory;
  }

  /// Returns the category value Finnhub expects.
  ///
  /// @return the Finnhub category, e.g. `general`
  public String finnhubCategory() {
    return finnhubCategory;
  }
}
