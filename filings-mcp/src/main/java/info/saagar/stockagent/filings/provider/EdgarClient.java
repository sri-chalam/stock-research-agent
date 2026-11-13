package info.saagar.stockagent.filings.provider;

import java.util.List;

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
}
