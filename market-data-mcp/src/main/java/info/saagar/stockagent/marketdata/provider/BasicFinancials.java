package info.saagar.stockagent.marketdata.provider;

import java.math.BigDecimal;

/// A fixed set of key metrics for one company, with no time series (MCP-2).
///
/// Any metric can be `null` when Finnhub has no value for it.
///
/// @param symbol ticker symbol
/// @param week52High 52-week high price
/// @param week52Low 52-week low price
/// @param peRatioTtm price/earnings ratio, trailing twelve months
/// @param priceToBook price/book ratio
/// @param epsTtm earnings per share, trailing twelve months
/// @param dividendYieldPercent indicated annual dividend yield, in percent
/// @param beta beta versus the market
/// @param grossMarginPercentTtm gross margin, trailing twelve months, in percent
/// @param operatingMarginPercentTtm operating margin, trailing twelve months, in percent
/// @param netMarginPercentTtm net profit margin, trailing twelve months, in percent
/// @param returnOnEquityPercentTtm return on equity, trailing twelve months, in percent
/// @param debtToEquity total debt / total equity, latest quarter
public record BasicFinancials(
    String symbol,
    BigDecimal week52High,
    BigDecimal week52Low,
    BigDecimal peRatioTtm,
    BigDecimal priceToBook,
    BigDecimal epsTtm,
    BigDecimal dividendYieldPercent,
    BigDecimal beta,
    BigDecimal grossMarginPercentTtm,
    BigDecimal operatingMarginPercentTtm,
    BigDecimal netMarginPercentTtm,
    BigDecimal returnOnEquityPercentTtm,
    BigDecimal debtToEquity) {}
