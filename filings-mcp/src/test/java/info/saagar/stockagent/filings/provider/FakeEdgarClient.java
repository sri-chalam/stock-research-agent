package info.saagar.stockagent.filings.provider;

import java.util.List;

/// In-memory [EdgarClient] that returns the same synthetic filings for every CIK and records the
/// last CIK it was asked for.
public class FakeEdgarClient implements EdgarClient {

  private final List<EdgarFiling> recentFilings;
  private volatile String lastRequestedCik;

  /// Creates the fake.
  ///
  /// @param recentFilings filings to return, newest first
  public FakeEdgarClient(List<EdgarFiling> recentFilings) {
    this.recentFilings = List.copyOf(recentFilings);
  }

  @Override
  public List<EdgarFiling> fetchRecentFilings(String cik) {
    lastRequestedCik = cik;
    return recentFilings;
  }

  /// Returns the CIK of the last call.
  ///
  /// @return the CIK, or `null` before the first call
  public String lastRequestedCik() {
    return lastRequestedCik;
  }
}
