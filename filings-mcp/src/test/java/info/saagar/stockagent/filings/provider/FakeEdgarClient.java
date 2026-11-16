package info.saagar.stockagent.filings.provider;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/// In-memory [EdgarClient] that returns the same synthetic filings and XBRL facts for every CIK and
/// records the last CIK it was asked for and every document URL it was asked to download.
public class FakeEdgarClient implements EdgarClient {

  private final List<EdgarFiling> recentFilings;
  private final List<XbrlFact> companyFacts;
  private final Map<String, String> filingHtmlByDocumentUrl;
  private final List<String> fetchedDocumentUrls = new CopyOnWriteArrayList<>();
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
    this(recentFilings, companyFacts, Map.of());
  }

  /// Creates the fake with filings and their primary documents.
  ///
  /// @param recentFilings filings to return, newest first
  /// @param filingHtmlByDocumentUrl document HTML by sec.gov URL; another URL fails as EDGAR would
  public FakeEdgarClient(
      List<EdgarFiling> recentFilings, Map<String, String> filingHtmlByDocumentUrl) {
    this(recentFilings, List.of(), filingHtmlByDocumentUrl);
  }

  private FakeEdgarClient(
      List<EdgarFiling> recentFilings,
      List<XbrlFact> companyFacts,
      Map<String, String> filingHtmlByDocumentUrl) {
    this.recentFilings = List.copyOf(recentFilings);
    this.companyFacts = List.copyOf(companyFacts);
    this.filingHtmlByDocumentUrl = Map.copyOf(filingHtmlByDocumentUrl);
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

  @Override
  public String fetchFilingDocument(String documentUrl) {
    fetchedDocumentUrls.add(documentUrl);
    String filingHtml = filingHtmlByDocumentUrl.get(documentUrl);
    if (filingHtml == null) {
      throw new FilingsException("SEC EDGAR rejected the request.");
    }
    return filingHtml;
  }

  /// Returns every document URL that was downloaded, in call order.
  ///
  /// @return the URLs; empty before the first download
  public List<String> fetchedDocumentUrls() {
    return List.copyOf(fetchedDocumentUrls);
  }

  /// Returns the CIK of the last call.
  ///
  /// @return the CIK, or `null` before the first call
  public String lastRequestedCik() {
    return lastRequestedCik;
  }
}
