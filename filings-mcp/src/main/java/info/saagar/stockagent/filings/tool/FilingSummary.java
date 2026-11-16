package info.saagar.stockagent.filings.tool;

/// One filing as `listFilings` returns it.
///
/// @param form form type, e.g. `10-K`
/// @param filingDate date filed, `YYYY-MM-DD`
/// @param reportPeriod end of the period the filing covers, `YYYY-MM-DD`; `null` when EDGAR has
///   none
/// @param accessionNumber accession number with dashes, e.g. `0000950170-25-100235`
/// @param url link to the main document on sec.gov
public record FilingSummary(
    String form, String filingDate, String reportPeriod, String accessionNumber, String url) {}
