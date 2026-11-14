package info.saagar.stockagent.filings.provider;

import java.util.List;
import java.util.Set;

/// Calls to the SEC EDGAR APIs.
///
/// Arguments are already validated by the caller. Every method throws [FilingsException] when the
/// call fails; its message is safe to show to the model.
public interface EdgarClient {

  /// Fetches a company's recent filings from `/submissions/CIK{cik}.json` (`filings.recent`).
  ///
  /// @param cik 10-digit, zero-padded CIK, e.g. `0000789019`
  /// @return the filings in EDGAR's order, newest first; empty when EDGAR lists none
  /// @throws FilingsException when the call fails; the message is safe to show to the model
  List<EdgarFiling> fetchRecentFilings(String cik);

  /// Fetches a company's US-GAAP facts from `/api/xbrl/companyfacts/CIK{cik}.json`, keeping only
  /// the named concepts.
  ///
  /// @param cik 10-digit, zero-padded CIK, e.g. `0000789019`
  /// @param conceptNames US-GAAP tags to keep, e.g. `NetIncomeLoss`
  /// @return the facts of those concepts in every unit; empty when the company reports none
  /// @throws FilingsException when the call fails; the message is safe to show to the model
  List<XbrlFact> fetchCompanyFacts(String cik, Set<String> conceptNames);

  /// Downloads a filing's primary document from `www.sec.gov/Archives`.
  ///
  /// @param documentUrl the document's sec.gov link, from [EdgarFiling#documentUrl(String)]
  /// @return the document's HTML
  /// @throws FilingsException when the call fails; the message is safe to show to the model
  String fetchFilingDocument(String documentUrl);
}
