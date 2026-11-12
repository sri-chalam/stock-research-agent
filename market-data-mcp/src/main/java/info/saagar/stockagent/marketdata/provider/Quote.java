package info.saagar.stockagent.marketdata.provider;

import java.math.BigDecimal;

/// The latest price of one US stock, trimmed to the fields the model needs.
///
/// Prices are in USD. There is no company name: Finnhub's `/quote` has none, and a second call
/// would spend rate-limit budget.
///
/// @param symbol ticker symbol, e.g. `AAPL`
/// @param currentPrice latest traded price
/// @param change change versus the previous close
/// @param percentChange change versus the previous close, in percent
/// @param high high of the current trading day
/// @param low low of the current trading day
/// @param open open of the current trading day
/// @param previousClose previous day's close
/// @param asOfTime time the price was current, ISO-8601 in UTC, e.g. `2026-05-01T14:30:00Z`
public record Quote(
    String symbol,
    BigDecimal currentPrice,
    BigDecimal change,
    BigDecimal percentChange,
    BigDecimal high,
    BigDecimal low,
    BigDecimal open,
    BigDecimal previousClose,
    String asOfTime) {}
