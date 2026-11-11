package info.saagar.stockagent.provider;

import java.math.BigDecimal;
import java.time.Instant;

/// A point-in-time price quote for a US equity.
///
/// @param symbol ticker symbol, e.g. `AAPL`
/// @param companyName display name of the company
/// @param price last traded price in USD
/// @param percentChange change versus the previous close, in percent
/// @param asOf time the quote was captured
public record Quote(
    String symbol, String companyName, BigDecimal price, BigDecimal percentChange, Instant asOf) {}
