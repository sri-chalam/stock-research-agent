package info.saagar.stockagent.filings.tool;

/// One filing chunk as `searchFilings` returns it, with the fields needed to cite it.
///
/// @param text the chunk's plain text
/// @param form form type, e.g. `10-K`
/// @param fiscalPeriod the filing's report period, `YYYY-MM-DD`
/// @param section section label, e.g. `Item 1A`
/// @param accessionNumber accession number with dashes
/// @param url link to the filing's primary document on sec.gov
/// @param score similarity to the query, from 0 to 1; higher is more similar
public record FilingChunk(
    String text,
    String form,
    String fiscalPeriod,
    String section,
    String accessionNumber,
    String url,
    double score) {}
