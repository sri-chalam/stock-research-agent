package info.saagar.stockagent.filings.rag;

/// Metadata keys stored with every filing chunk, written by ingestion and read by `searchFilings`.
public final class FilingChunkMetadata {

  /// Upper-case ticker, e.g. `AAPL`; `searchFilings` filters on it.
  public static final String TICKER = "ticker";

  /// Form type, e.g. `10-K`.
  public static final String FORM = "form";

  /// The filing's report period (end of the period covered), `YYYY-MM-DD`.
  public static final String FISCAL_PERIOD = "fiscalPeriod";

  /// Section label, e.g. `Item 1A`.
  public static final String SECTION = "section";

  /// Accession number with dashes; ingestion replaces a filing's chunks by it.
  public static final String ACCESSION_NUMBER = "accessionNumber";

  /// Link to the filing's primary document on sec.gov.
  public static final String URL = "url";

  private FilingChunkMetadata() {}
}
