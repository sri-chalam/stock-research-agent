package info.saagar.stockagent.filings.provider;

import java.util.List;
import java.util.Set;

/// In-memory [EdgarClient] that returns the same synthetic filings and XBRL facts for every CIK and
/// records the last CIK it was asked for.
public class FakeEdgarClient implements EdgarClient {

  private final List<EdgarFiling> recentFilings;
  private final List<XbrlFact> companyFacts;
  private volatile String lastRequestedCik;

  /// Creates the fake with filings and no XBRL facts.
  ///
  /// @param recentFilings filings to return, newest first
  public FakeEdgarClient(List<EdgarFiling> recentFilings) {
    this(recentFilings, List.of());
  }

  /// Creates the fake.
  ///
  /// @param recentFilings filings to return, newest first
  /// @param companyFacts XBRL facts; `fetchCompanyFacts` returns those of the requested concepts
  public FakeEdgarClient(List<EdgarFiling> recentFilings, List<XbrlFact> companyFacts) {
    this.recentFilings = List.copyOf(recentFilings);
    this.companyFacts = List.copyOf(companyFacts);
  }

  @Override
  public List<EdgarFiling> fetchRecentFilings(String cik) {
    lastRequestedCik = cik;
    return recentFilings;
  }

  @Override
  public List<XbrlFact> fetchCompanyFacts(String cik, Set<String> conceptNames) {
    lastRequestedCik = cik;
    return companyFacts.stream()
        .filter(xbrlFact -> conceptNames.contains(xbrlFact.concept()))
        .toList();
  }

  /// Returns the CIK of the last call.
  ///
  /// @return the CIK, or `null` before the first call
  public String lastRequestedCik() {
    return lastRequestedCik;
  }
}
