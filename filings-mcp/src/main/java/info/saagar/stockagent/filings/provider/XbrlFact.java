package info.saagar.stockagent.filings.provider;

import java.math.BigDecimal;

/// One US-GAAP XBRL fact from a company's EDGAR `companyfacts`, with only the fields the tools
/// read.
///
/// @param concept US-GAAP tag, e.g. `NetIncomeLoss`
/// @param unit unit of measure, e.g. `USD` or `USD/shares`
/// @param start start of the period, `YYYY-MM-DD`; `null` for a point-in-time value such as
///   `Assets`
/// @param end end of the period, or the date of a point-in-time value, `YYYY-MM-DD`
/// @param value the reported number
/// @param form form of the filing that reported it, e.g. `10-K`
/// @param fiscalPeriod fiscal period of that filing, e.g. `FY` or `Q2`
/// @param filedDate date that filing was filed, `YYYY-MM-DD`
/// @param accessionNumber accession number of that filing, with dashes
public record XbrlFact(
    String concept,
    String unit,
    String start,
    String end,
    BigDecimal value,
    String form,
    String fiscalPeriod,
    String filedDate,
    String accessionNumber) {}
