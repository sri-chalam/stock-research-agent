package info.saagar.stockagent.filings.provider;

/// One filing from a company's EDGAR submissions, with only the fields the tools read.
///
/// @param form form type, e.g. `10-K`
/// @param filingDate date filed, `YYYY-MM-DD`
/// @param reportDate end of the period the filing covers, `YYYY-MM-DD`; `null` when EDGAR has none
/// @param accessionNumber accession number with dashes, e.g. `0000950170-25-100235`
/// @param primaryDocument file name of the main document, e.g. `msft-20250630.htm`
public record EdgarFiling(
    String form,
    String filingDate,
    String reportDate,
    String accessionNumber,
    String primaryDocument) {

  private static final String ARCHIVES_URL = "https://www.sec.gov/Archives/edgar/data/";

  /// Builds the link to this filing's primary document on sec.gov.
  ///
  /// @param cik the filer's 10-digit, zero-padded CIK
  /// @return e.g. `https://www.sec.gov/Archives/edgar/data/789019/000095017025100235/msft.htm`
  public String documentUrl(String cik) {
    return ARCHIVES_URL
        + Long.parseLong(cik)
        + "/"
        + accessionNumber.replace("-", "")
        + "/"
        + primaryDocument;
  }
}
